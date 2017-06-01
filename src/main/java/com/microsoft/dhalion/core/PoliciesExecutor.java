/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 *
 * This program is made available under the terms of the MIT License.
 * See the LICENSE file in the project root for more information.
 */

package com.microsoft.dhalion.core;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.microsoft.dhalion.api.IHealthPolicy;
import com.microsoft.dhalion.api.IResolver;
import com.microsoft.dhalion.detector.Symptom;
import com.microsoft.dhalion.diagnoser.Diagnosis;
import com.microsoft.dhalion.resolver.Action;

public class PoliciesExecutor {
  private static final Logger LOG = Logger.getLogger(PoliciesExecutor.class.getName());
  private final List<PolicySchedulingInfo> policies;
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

  public PoliciesExecutor(List<IHealthPolicy> policies) {
    this.policies = policies.stream().map(PolicySchedulingInfo::new).collect(Collectors.toList());
  }

  public ScheduledFuture<?> start() {
    ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
      // schedule the next execution cycle
      Long nextScheduleDelay = policies.stream()
          .map(x -> x.getDelay()).min(Comparator.naturalOrder()).orElse(1000l);
      if (nextScheduleDelay > 0) {
        try {
          LOG.info("Sleep (millis) before next policy execution cycle: " + nextScheduleDelay);
          TimeUnit.MILLISECONDS.sleep(nextScheduleDelay);
        } catch (InterruptedException e) {
          LOG.warning("Interrupted while waiting for next policy execution cycle");
        }
      }

      for (PolicySchedulingInfo policySchedulingInfo : policies) {
        IHealthPolicy policy = policySchedulingInfo.policy;
        if (policySchedulingInfo.getDelay() > 0) {
          continue;
        }

        LOG.info("Executing Policy: " + policy.getClass().getSimpleName());
        List<Symptom> symptoms = policy.executeDetectors();
        List<Diagnosis> diagnosis = policy.executeDiagnosers(symptoms);
        IResolver resolver = policy.selectResolver(diagnosis);
        List<Action> actions = policy.executeResolvers(resolver);

        policySchedulingInfo.lastExecutionTimeMills = System.currentTimeMillis();

        // notify all action listeners
        if (actions != null) {
          for (Action action : actions) {
            policies.stream().forEach(x -> x.policy.onUpdate(action));
          }
        }
      }

    }, 1, 1, TimeUnit.MILLISECONDS);

    return future;
  }

  public void destroy() {
    this.executor.shutdownNow();
  }

  private class PolicySchedulingInfo {
    private long intervalMills;
    private long lastExecutionTimeMills = 0;
    private IHealthPolicy policy;

    public PolicySchedulingInfo(IHealthPolicy policy) {
      this.intervalMills = policy.getInterval();
      this.policy = policy;
    }

    long getDelay() {
      long currentTime = System.currentTimeMillis();
      long nextExecutionTime =
          lastExecutionTimeMills == 0 ? currentTime : lastExecutionTimeMills + intervalMills;
      return nextExecutionTime - currentTime;
    }
  }
}