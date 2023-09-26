/*
 * Copyright 2019-present HiveMQ GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hivemq.edge.modules.adapters.impl;

import com.google.common.base.Preconditions;
import com.hivemq.common.shutdown.HiveMQShutdownHook;
import com.hivemq.common.shutdown.ShutdownHooks;
import com.hivemq.edge.modules.adapters.params.ProtocolAdapterPollingInput;
import com.hivemq.edge.modules.adapters.params.ProtocolAdapterPollingOutput;
import com.hivemq.edge.modules.adapters.params.impl.ProtocolAdapterPollingOutputImpl;
import com.hivemq.edge.modules.api.adapters.ProtocolAdapter;
import com.hivemq.edge.modules.api.adapters.ProtocolAdapterPollingService;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The polling service provides utility to track and invoke adapter
 * data acquisition attempts. It provides a mechanism to handle
 * errors and back off the attempts until a maximum number of
 * retries occurs, at which point a terminal failure callback
 * is invoked and the instance is removed from the schedule and close
 * is called on the input instance (probably closing the underlying
 * resource according to the implementation).
 *
 * @author Simon L Johnson
 */
public class ProtocolAdapterPollingServiceImpl implements ProtocolAdapterPollingService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolAdapterPollingServiceImpl.class);
    private static long MAX_BACKOFF_MILLIS = 60000 * 10; //-- 10 Mins
    private final @NotNull ScheduledExecutorService scheduledExecutorService;
    private final @NotNull Map<ProtocolAdapterPollingOutput, MonitoredPollingJob> activePollers =
            new ConcurrentHashMap<>();

    @Inject
    public ProtocolAdapterPollingServiceImpl(final @NotNull ScheduledExecutorService scheduledExecutorService,
                                             final @NotNull ShutdownHooks shutdownHooks) {
        this.scheduledExecutorService = scheduledExecutorService;
        shutdownHooks.add(new HiveMQShutdownHook() {
            @Override
            public @NotNull String name() {
                return "Protocol Adapter Polling Service ShutDown";
            }

            @Override
            public void run() {
                if(!scheduledExecutorService.isShutdown()){
                    try {
                        scheduledExecutorService.shutdown();
                        if(!scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS)){
                            scheduledExecutorService.shutdownNow();
                        }
                    } catch(InterruptedException e){
                        log.warn("Error Encountered Attempting to Shutdown Adapter Polling Service", e);
                    }
                }
            }
        });
    }

    public ProtocolAdapterPollingOutput schedulePolling(final @NotNull ProtocolAdapter adapter,
                                                         final @NotNull ProtocolAdapterPollingInput input){

        if(log.isTraceEnabled()){
            log.trace("Scheduling Polling For Adapter {}", adapter.getId());
        }
        ProtocolAdapterPollingOutput output = new ProtocolAdapterPollingOutputImpl(adapter.getId(), input);
        MonitoredPollingJob internalJob = new MonitoredPollingJob(input, output);
        ScheduledFuture<?> future = scheduledExecutorService.scheduleAtFixedRate(internalJob,
                input.getInitialDelay(),
                input.getPeriod(),
                input.getUnit());
        output.setFuture(future);
        activePollers.put(output, internalJob);
        return output;
    }


    public Optional<ProtocolAdapterPollingOutput> getPollingJob(final @NotNull UUID id){
        Preconditions.checkNotNull(id);
        return activePollers.keySet().stream().filter(p -> p.getId().equals(id)).findAny();
    }

    public List<ProtocolAdapterPollingOutput> getPollingJobsForAdapter(final @NotNull String adapterId){
        Preconditions.checkNotNull(adapterId);
        return activePollers.keySet().stream().
                filter(p -> p.getAdapterId().equals(adapterId)).
                collect(Collectors.toList());
    }

    public void stopPollingForAdapterInstance(final @NotNull ProtocolAdapter adapter){
        Preconditions.checkNotNull(adapter);
        activePollers.keySet().stream().
                filter(p -> p.getAdapterId().equals(adapter.getId())).
                forEach(this::stopPolling);
    }

    public void stopPolling(final @NotNull ProtocolAdapterPollingOutput pollingJob){
        Preconditions.checkNotNull(pollingJob);
        if(activePollers.remove(pollingJob) != null){
            Future<?> future = pollingJob.getFuture();
            if(!future.isCancelled()){
                if(log.isInfoEnabled()){
                    log.info("Stopping Polling Job {}", getJobId(pollingJob));
                }
                if(future.cancel(true)){
                    pollingJob.getInput().close();
                }
            }
        }
    }

    protected static String getJobId(final @NotNull ProtocolAdapterPollingOutput pollingJob){
        return String.format("%s:%s", pollingJob.getAdapterId(), pollingJob.getId());
    }

    @Override
    public List<ProtocolAdapterPollingOutput> getActiveProcesses() {
        return Collections.unmodifiableList(
                    new ArrayList<>(activePollers.keySet()));
    }

    @Override
    public int currentErrorCount(final ProtocolAdapterPollingOutput pollingJob) {
        return activePollers.get(pollingJob).errorCount.get();
    }

    public void stopAllPolling(){
        activePollers.keySet().stream().forEach(this::stopPolling);
    }

    private static long getBackoff(int errorCount, long max, boolean addFuzziness){
        //-- This will backoff up to a max of about a day (unless the max provided is less)
        long f = (long) (Math.pow(2, Math.min(errorCount, 20)) * 100);
        if(addFuzziness){
            f += ThreadLocalRandom.current().nextInt(0, errorCount * 100);
        }
        f =  Math.min(f, max);
        return f;
    }

    private class MonitoredPollingJob implements Runnable {
        private final AtomicInteger errorCount = new AtomicInteger(0);
        private final ProtocolAdapterPollingInput input;
        private final ProtocolAdapterPollingOutput output;
        private volatile long notBefore = 0;

        public MonitoredPollingJob(final ProtocolAdapterPollingInput input,
                                   final ProtocolAdapterPollingOutput output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {
            try {
                if(notBefore > 0){
                    if(System.currentTimeMillis() < notBefore){
                        //-- We're backing off atm so as not to harass the network
                        if(log.isDebugEnabled()){
                            log.debug("Backing Off Polling Job {} Error Count {}",
                                    getJobId(output), errorCount);
                        }
                        return;
                    }
                }
                input.execute();
                errorCount.set(0);
                notBefore = 0;
            } catch(Throwable e){
                if(log.isInfoEnabled()){
                    log.info("Polling Job {} Resulted In Error -> {}",
                            getJobId(output), e.getMessage());
                }
                if(log.isDebugEnabled()){
                    log.debug("Original exception:", e);
                }
                if(input.getMaxErrorsBeforeRemoval() <=
                        errorCount.incrementAndGet()) {
                    onTerminalError(e, errorCount.get());
                    //-- rest the error state
                    notBefore = 0;
                    errorCount.set(0);
                } else {
                    //exp. backoff the network call according to the number of errors
                    long backoff = getBackoff(errorCount.get(), MAX_BACKOFF_MILLIS,true);
                    notBefore = System.currentTimeMillis() + backoff;
                }
            }
        }

        protected void onTerminalError(Throwable t, int errorCount){
            log.warn("Job {} Hit Terminal Error Threshold {}",
                    getJobId(output), errorCount, t);
            stopPolling(output);
        }
    }
}
