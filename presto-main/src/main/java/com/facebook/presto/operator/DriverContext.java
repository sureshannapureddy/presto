/*
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
package com.facebook.presto.operator;

import com.facebook.presto.Session;
import com.facebook.presto.execution.Lifespan;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.memory.QueryContextVisitor;
import com.facebook.presto.memory.context.MemoryTrackingContext;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.stats.CounterStat;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.transform;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.airlift.units.DataSize.succinctBytes;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;

/**
 * Only calling getDriverStats is ThreadSafe
 */
public class DriverContext
{
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private final PipelineContext pipelineContext;
    private final Executor notificationExecutor;
    private final ScheduledExecutorService yieldExecutor;

    private final AtomicBoolean finished = new AtomicBoolean();

    private final DateTime createdTime = DateTime.now();
    private final long createNanos = System.nanoTime();

    private final AtomicLong startNanos = new AtomicLong();
    private final AtomicLong endNanos = new AtomicLong();

    private final AtomicLong intervalWallStart = new AtomicLong();
    private final AtomicLong intervalCpuStart = new AtomicLong();

    private final AtomicLong processCalls = new AtomicLong();
    private final AtomicLong processWallNanos = new AtomicLong();
    private final AtomicLong processCpuNanos = new AtomicLong();

    private final AtomicReference<BlockedMonitor> blockedMonitor = new AtomicReference<>();
    private final AtomicLong blockedWallNanos = new AtomicLong();

    private final AtomicReference<DateTime> executionStartTime = new AtomicReference<>();
    private final AtomicReference<DateTime> executionEndTime = new AtomicReference<>();

    private final MemoryTrackingContext driverMemoryContext;

    private final DriverYieldSignal yieldSignal;

    private final List<OperatorContext> operatorContexts = new CopyOnWriteArrayList<>();
    private final boolean partitioned;
    private final Lifespan lifespan;

    public DriverContext(
            PipelineContext pipelineContext,
            Executor notificationExecutor,
            ScheduledExecutorService yieldExecutor,
            MemoryTrackingContext driverMemoryContext,
            boolean partitioned,
            Lifespan lifespan)
    {
        this.pipelineContext = requireNonNull(pipelineContext, "pipelineContext is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");
        this.yieldExecutor = requireNonNull(yieldExecutor, "scheduler is null");
        this.driverMemoryContext = requireNonNull(driverMemoryContext, "driverMemoryContext is null");
        this.partitioned = partitioned;
        this.lifespan = requireNonNull(lifespan, "lifespan is null");
        this.yieldSignal = new DriverYieldSignal();
    }

    public TaskId getTaskId()
    {
        return pipelineContext.getTaskId();
    }

    public OperatorContext addOperatorContext(int operatorId, PlanNodeId planNodeId, String operatorType)
    {
        checkArgument(operatorId >= 0, "operatorId is negative");

        for (OperatorContext operatorContext : operatorContexts) {
            checkArgument(operatorId != operatorContext.getOperatorId(), "A context already exists for operatorId %s", operatorId);
        }

        OperatorContext operatorContext = new OperatorContext(
                operatorId,
                planNodeId,
                operatorType,
                this,
                notificationExecutor,
                driverMemoryContext.newMemoryTrackingContext());
        operatorContexts.add(operatorContext);
        return operatorContext;
    }

    public List<OperatorContext> getOperatorContexts()
    {
        return ImmutableList.copyOf(operatorContexts);
    }

    public PipelineContext getPipelineContext()
    {
        return pipelineContext;
    }

    public Session getSession()
    {
        return pipelineContext.getSession();
    }

    public void startProcessTimer()
    {
        if (startNanos.compareAndSet(0, System.nanoTime())) {
            pipelineContext.start();
            executionStartTime.set(DateTime.now());
        }

        intervalWallStart.set(System.nanoTime());
        intervalCpuStart.set(currentThreadCpuTime());
    }

    public void recordProcessed()
    {
        processCalls.incrementAndGet();
        processWallNanos.getAndAdd(nanosBetween(intervalWallStart.get(), System.nanoTime()));
        processCpuNanos.getAndAdd(nanosBetween(intervalCpuStart.get(), currentThreadCpuTime()));
    }

    public void recordBlocked(ListenableFuture<?> blocked)
    {
        requireNonNull(blocked, "blocked is null");

        BlockedMonitor monitor = new BlockedMonitor();

        BlockedMonitor oldMonitor = blockedMonitor.getAndSet(monitor);
        if (oldMonitor != null) {
            oldMonitor.run();
        }

        blocked.addListener(monitor, notificationExecutor);
    }

    public void finished()
    {
        if (!finished.compareAndSet(false, true)) {
            // already finished
            return;
        }
        executionEndTime.set(DateTime.now());
        endNanos.set(System.nanoTime());

        pipelineContext.driverFinished(this);
    }

    public void failed(Throwable cause)
    {
        pipelineContext.failed(cause);
        finished.set(true);
    }

    public boolean isDone()
    {
        return finished.get() || pipelineContext.isDone();
    }

    public ListenableFuture<?> reserveSpill(long bytes)
    {
        return pipelineContext.reserveSpill(bytes);
    }

    public void freeSpill(long bytes)
    {
        if (bytes == 0) {
            return;
        }
        checkArgument(bytes > 0, "bytes is negative");
        pipelineContext.freeSpill(bytes);
    }

    public DriverYieldSignal getYieldSignal()
    {
        return yieldSignal;
    }

    public long getSystemMemoryUsage()
    {
        return driverMemoryContext.getSystemMemory();
    }

    public long getMemoryUsage()
    {
        return driverMemoryContext.getUserMemory();
    }

    public long getRevocableMemoryUsage()
    {
        return driverMemoryContext.getRevocableMemory();
    }

    public void moreMemoryAvailable()
    {
        operatorContexts.forEach(OperatorContext::moreMemoryAvailable);
    }

    public boolean isVerboseStats()
    {
        return pipelineContext.isVerboseStats();
    }

    public boolean isCpuTimerEnabled()
    {
        return pipelineContext.isCpuTimerEnabled();
    }

    public CounterStat getInputDataSize()
    {
        OperatorContext inputOperator = getFirst(operatorContexts, null);
        if (inputOperator != null) {
            return inputOperator.getInputDataSize();
        }
        else {
            return new CounterStat();
        }
    }

    public CounterStat getInputPositions()
    {
        OperatorContext inputOperator = getFirst(operatorContexts, null);
        if (inputOperator != null) {
            return inputOperator.getInputPositions();
        }
        else {
            return new CounterStat();
        }
    }

    public CounterStat getOutputDataSize()
    {
        OperatorContext inputOperator = getLast(operatorContexts, null);
        if (inputOperator != null) {
            return inputOperator.getOutputDataSize();
        }
        else {
            return new CounterStat();
        }
    }

    public CounterStat getOutputPositions()
    {
        OperatorContext inputOperator = getLast(operatorContexts, null);
        if (inputOperator != null) {
            return inputOperator.getOutputPositions();
        }
        else {
            return new CounterStat();
        }
    }

    public long getPphysicalWrittenDataSize()
    {
        return operatorContexts.stream()
                .mapToLong(OperatorContext::getPhysicalWrittenDataSize)
                .sum();
    }

    public boolean isExecutionStarted()
    {
        return executionStartTime.get() != null;
    }

    public boolean isFullyBlocked()
    {
        return blockedMonitor.get() != null;
    }

    public DriverStats getDriverStats()
    {
        long totalScheduledTime = processWallNanos.get();
        long totalCpuTime = processCpuNanos.get();

        long totalBlockedTime = blockedWallNanos.get();
        BlockedMonitor blockedMonitor = this.blockedMonitor.get();
        if (blockedMonitor != null) {
            totalBlockedTime += blockedMonitor.getBlockedTime();
        }

        List<OperatorStats> operators = ImmutableList.copyOf(transform(operatorContexts, OperatorContext::getOperatorStats));
        OperatorStats inputOperator = getFirst(operators, null);
        DataSize rawInputDataSize;
        long rawInputPositions;
        Duration rawInputReadTime;
        DataSize processedInputDataSize;
        long processedInputPositions;
        DataSize outputDataSize;
        long outputPositions;
        if (inputOperator != null) {
            rawInputDataSize = inputOperator.getInputDataSize();
            rawInputPositions = inputOperator.getInputPositions();
            rawInputReadTime = inputOperator.getAddInputWall();

            processedInputDataSize = inputOperator.getOutputDataSize();
            processedInputPositions = inputOperator.getOutputPositions();

            OperatorStats outputOperator = requireNonNull(getLast(operators, null));
            outputDataSize = outputOperator.getOutputDataSize();
            outputPositions = outputOperator.getOutputPositions();
        }
        else {
            rawInputDataSize = new DataSize(0, BYTE);
            rawInputPositions = 0;
            rawInputReadTime = new Duration(0, MILLISECONDS);

            processedInputDataSize = new DataSize(0, BYTE);
            processedInputPositions = 0;

            outputDataSize = new DataSize(0, BYTE);
            outputPositions = 0;
        }

        long physicalWrittenDataSize = operators.stream()
                .map(OperatorStats::getPhysicalWrittenDataSize)
                .mapToLong(DataSize::toBytes)
                .sum();

        long startNanos = this.startNanos.get();
        if (startNanos < createNanos) {
            startNanos = System.nanoTime();
        }
        Duration queuedTime = new Duration(startNanos - createNanos, NANOSECONDS);

        long endNanos = this.endNanos.get();
        Duration elapsedTime;
        if (endNanos >= startNanos) {
            elapsedTime = new Duration(endNanos - createNanos, NANOSECONDS);
        }
        else {
            elapsedTime = new Duration(0, NANOSECONDS);
        }

        ImmutableSet.Builder<BlockedReason> builder = ImmutableSet.builder();

        for (OperatorStats operator : operators) {
            if (operator.getBlockedReason().isPresent()) {
                builder.add(operator.getBlockedReason().get());
            }
        }

        return new DriverStats(
                lifespan,
                createdTime,
                executionStartTime.get(),
                executionEndTime.get(),
                queuedTime.convertToMostSuccinctTimeUnit(),
                elapsedTime.convertToMostSuccinctTimeUnit(),
                succinctBytes(driverMemoryContext.getUserMemory()),
                succinctBytes(driverMemoryContext.getRevocableMemory()),
                succinctBytes(driverMemoryContext.getSystemMemory()),
                new Duration(totalScheduledTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                new Duration(totalCpuTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                new Duration(totalBlockedTime, NANOSECONDS).convertToMostSuccinctTimeUnit(),
                blockedMonitor != null,
                builder.build(),
                rawInputDataSize.convertToMostSuccinctDataSize(),
                rawInputPositions,
                rawInputReadTime,
                processedInputDataSize.convertToMostSuccinctDataSize(),
                processedInputPositions,
                outputDataSize.convertToMostSuccinctDataSize(),
                outputPositions,
                succinctBytes(physicalWrittenDataSize),
                ImmutableList.copyOf(transform(operatorContexts, OperatorContext::getOperatorStats)));
    }

    public <C, R> R accept(QueryContextVisitor<C, R> visitor, C context)
    {
        return visitor.visitDriverContext(this, context);
    }

    public <C, R> List<R> acceptChildren(QueryContextVisitor<C, R> visitor, C context)
    {
        return operatorContexts.stream()
                .map(operatorContext -> operatorContext.accept(visitor, context))
                .collect(toList());
    }

    public boolean isPartitioned()
    {
        return partitioned;
    }

    public Lifespan getLifespan()
    {
        return lifespan;
    }

    public ScheduledExecutorService getYieldExecutor()
    {
        return yieldExecutor;
    }

    private long currentThreadCpuTime()
    {
        if (!isCpuTimerEnabled()) {
            return 0;
        }
        return THREAD_MX_BEAN.getCurrentThreadCpuTime();
    }

    private static long nanosBetween(long start, long end)
    {
        return Math.abs(end - start);
    }

    private class BlockedMonitor
            implements Runnable
    {
        private final long start = System.nanoTime();
        private boolean finished;

        @Override
        public void run()
        {
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
                blockedMonitor.compareAndSet(this, null);
                blockedWallNanos.getAndAdd(getBlockedTime());
            }
        }

        public long getBlockedTime()
        {
            return nanosBetween(start, System.nanoTime());
        }
    }

    @VisibleForTesting
    public MemoryTrackingContext getDriverMemoryContext()
    {
        return driverMemoryContext;
    }
}
