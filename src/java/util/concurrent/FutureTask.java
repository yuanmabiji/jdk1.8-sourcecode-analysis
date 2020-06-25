/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    /** 任务执行状态 */
    private volatile int state;
    /** 任务新建状态 */
    private static final int NEW          = 0;
    /** 任务正在完成状态，是一个瞬间过渡状态 */
    private static final int COMPLETING   = 1;
    /** 任务正常结束状态 */
    private static final int NORMAL       = 2;
    /** 任务执行异常状态 */
    private static final int EXCEPTIONAL  = 3;
    /** 任务被取消状态，对应cancel(false) */
    private static final int CANCELLED    = 4;
    /** 任务中断状态，是一个瞬间过渡状态 */
    private static final int INTERRUPTING = 5;
    /** 任务被中断状态，对应cancel(true) */
    private static final int INTERRUPTED  = 6;

    /** The underlying callable; nulled out after running */
    /** 封装的Callable对象，其call方法用来执行异步任务 */
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    /** 在FutureTask里面定义一个成员变量outcome，用来装异步任务的执行结果 */
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    /** 用来执行callable任务的线程 */
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    /** 获取异步任务结果的线程等待链表节点，reiber stack的一种实现 */
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        // 将异步任务执行结果赋值给x，此时FutureTask的成员变量outcome要么保存着
        // 异步任务正常执行的结果，要么保存着异步任务执行过程中抛出的异常
        Object x = outcome;
        // 【1】若异步任务正常执行结束，此时返回异步任务执行结果即可
        if (s == NORMAL)
            return (V)x;
        // 【2】若异步任务执行过程中，其他线程执行过cancel方法，此时抛出CancellationException异常
        if (s >= CANCELLED)
            throw new CancellationException();
        // 【3】若异步任务执行过程中，抛出异常，此时将该异常转换成ExecutionException后，重新抛出。
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        // 【1】判断当前任务状态，若state == NEW时根据mayInterruptIfRunning参数值给当前任务状态赋值为INTERRUPTING或CANCELLED
        // a）当任务状态不为NEW时，说明异步任务已经完成，或抛出异常，或已经被取消，此时直接返回false。
        // TODO 【问题】此时若state = COMPLETING呢？此时为何也直接返回false，而不能发出中断异步任务线程的中断信号呢？？
        // TODO 仅仅因为COMPLETING是一个瞬时态吗？？？
        // b）当前仅当任务状态为NEW时，此时若mayInterruptIfRunning为true，此时任务状态赋值为INTERRUPTING；否则赋值为CANCELLED。
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            // 【2】如果mayInterruptIfRunning为true，此时中断执行异步任务的线程runner（还记得执行异步任务时就把执行异步任务的线程就赋值给了runner成员变量吗）
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        // 中断执行异步任务的线程runner
                        t.interrupt();
                } finally { // final state
                    // 最后任务状态赋值为INTERRUPTED
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
            // 【3】不管mayInterruptIfRunning为true还是false，此时都要调用finishCompletion方法唤醒阻塞的获取异步任务结果的线程并移除线程等待链表节点
        } finally {
            finishCompletion();
        }
        // 返回true
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        // 【1】若任务状态<=COMPLETING，说明任务正在执行过程中，此时可能正常结束，也可能遇到异常
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        // 【2】最后根据任务状态来返回任务执行结果，此时有三种情况：1）任务正常执行；2）任务执行异常；3）任务被取消
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        // 【1】调用UNSAFE的CAS方法判断任务当前状态是否为NEW，若为NEW，则设置任务状态为COMPLETING
        // 【思考】此时任务不能被多线程并发执行，什么情况下会导致任务状态不为NEW？
        // 答案是只有在调用了cancel方法的时候，此时任务状态不为NEW，此时什么都不需要做，
        // 因此需要调用CAS方法来做判断任务状态是否为NEW
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 【2】将任务执行结果赋值给成员变量outcome
            outcome = v;
            // 【3】将任务状态设置为NORMAL，表示任务正常结束
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            // 【4】调用任务执行完成方法，此时会唤醒阻塞的线程，调用done()方法和清空等待线程链表等
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        // 【1】调用UNSAFE的CAS方法判断任务当前状态是否为NEW，若为NEW，则设置任务状态为COMPLETING
        // 【思考】此时任务不能被多线程并发执行，什么情况下会导致任务状态不为NEW？
        // 答案是只有在调用了cancel方法的时候，此时任务状态不为NEW，此时什么都不需要做，
        // 因此需要调用CAS方法来做判断任务状态是否为NEW
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 【2】将异常赋值给成员变量outcome
            outcome = t;
            // 【3】将任务状态设置为EXCEPTIONAL，表示任务异常结束
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            // 【4】调用任务执行完成方法，此时会唤醒阻塞的线程，调用done()方法和清空等待线程链表等
            finishCompletion();
        }
    }

    public void run() {
        // 【1】,为了防止多线程并发执行异步任务，这里需要判断线程满不满足执行异步任务的条件，有以下三种情况：
        // 1）若任务状态state为NEW且runner为null，说明还未有线程执行过异步任务，此时满足执行异步任务的条件，
        // 此时同时调用CAS方法为成员变量runner设置当前线程的值；
        // 2）若任务状态state为NEW且runner不为null，任务状态虽为NEW但runner不为null，说明有线程正在执行异步任务，
        // 此时不满足执行异步任务的条件，直接返回；
        // 1）若任务状态state不为NEW，此时不管runner是否为null，说明已经有线程执行过异步任务，此时没必要再重新
        // 执行一次异步任务，此时不满足执行异步任务的条件；
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        try {
            // 拿到之前构造函数传进来的callable实现类对象，其call方法封装了异步任务执行的逻辑
            Callable<V> c = callable;
            // 若任务还是新建状态的话，那么就调用异步任务
            if (c != null && state == NEW) {
                // 异步任务执行结果
                V result;
                // 异步任务执行成功还是始遍标志
                boolean ran;
                try {
                    // 【2】，执行异步任务逻辑，并把执行结果赋值给result
                    result = c.call();
                    // 若异步任务执行过程中没有抛出异常，说明异步任务执行成功，此时设置ran标志为true
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    // 异步任务执行过程抛出异常，此时设置ran标志为false
                    ran = false;
                    // 【3】设置异常，里面也设置state状态的变化
                    setException(ex);
                }
                // 【3】若异步任务执行成功，此时设置异步任务执行结果，同时也设置状态的变化
                if (ran)
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            // 异步任务正在执行过程中，runner一直是非空的，防止并发调用run方法，前面有调用cas方法做判断的
            // 在异步任务执行完后，不管是正常结束还是异常结束，此时设置runner为null
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            // 线程执行异步任务后的任务状态
            int s = state;
            // 【4】如果执行了cancel(true)方法，此时满足条件，
            // 此时调用handlePossibleCancellationInterrupt方法处理中断
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        // 当任务状态是INTERRUPTING时，此时让出CPU执行的机会，让其他线程执行
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        // 取出等待线程链表头节点，判断头节点是否为null
        // 1）若线程链表头节点不为空，此时以“后进先出”的顺序（栈）移除等待的线程WaitNode节点
        // 2）若线程链表头节点为空，说明还没有线程调用Future.get()方法来获取任务执行结果，固然不用移除
        for (WaitNode q; (q = waiters) != null;) {
            // 调用UNSAFE的CAS方法将成员变量waiters设置为空
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    // 取出WaitNode节点的线程
                    Thread t = q.thread;
                    // 若取出的线程不为null，则将该WaitNode节点线程置空，且唤醒正在阻塞的该线程
                    if (t != null) {
                        q.thread = null;
                        //【重要】唤醒正在阻塞的该线程
                        LockSupport.unpark(t);
                    }
                    // 继续取得下一个WaitNode线程节点
                    WaitNode next = q.next;
                    // 若没有下一个WaitNode线程节点，说明已经将所有等待的线程唤醒，此时跳出for循环
                    if (next == null)
                        break;
                    // 将已经移除的线程WaitNode节点的next指针置空，此时好被垃圾回收
                    q.next = null; // unlink to help gc
                    // 再把下一个WaitNode线程节点置为当前线程WaitNode头节点
                    q = next;
                }
                break;
            }
        }
        // 不管任务正常执行还是抛出异常，都会调用done方法
        done();
        // 因为异步任务已经执行完且结果已经保存到outcome中，因此此时可以将callable对象置空了
        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        // 计算超时结束时间
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        // 线程链表头节点
        WaitNode q = null;
        // 是否入队
        boolean queued = false;
        // 死循环
        for (;;) {
            // 如果当前获取任务执行结果的线程被中断，此时移除该线程WaitNode链表节点，并抛出InterruptedException
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            // 【5】如果任务状态>COMPLETING，此时返回任务执行结果，其中此时任务可能正常结束（NORMAL）,可能抛出异常（EXCEPTIONAL）
            // 或任务被取消（CANCELLED，INTERRUPTING或INTERRUPTED状态的一种）
            if (s > COMPLETING) {
                // 【问】此时将当前WaitNode节点的线程置空，其中在任务结束时也会调用finishCompletion将WaitNode节点的thread置空，
                // 这里为什么又要再调用一次q.thread = null;呢？
                // 【答】因为若很多线程来获取任务执行结果，在任务执行完的那一刻，此时获取任务的线程要么已经在线程等待链表中，要么
                // 此时还是一个孤立的WaitNode节点。在线程等待链表中的的所有WaitNode节点将由finishCompletion来移除（同时唤醒）所有
                // 等待的WaitNode节点，以便垃圾回收；而孤立的线程WaitNode节点此时还未阻塞，因此不需要被唤醒，此时只要把其属性置为
                // null，然后其有没有被谁引用，因此可以被GC。
                if (q != null)
                    q.thread = null;
                // 【重要】返回任务执行结果
                return s;
            }
            // 【4】若任务状态为COMPLETING，此时说明任务正在执行过程中，此时获取任务结果的线程需让出CPU执行时间片段
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            // 【1】若当前线程还没有进入线程等待链表的WaitNode节点，此时新建一个WaitNode节点，并把当前线程赋值给WaitNode节点的thread属性
            else if (q == null)
                q = new WaitNode();
            // 【2】若当前线程等待节点还未入线程等待队列，此时加入到该线程等待队列的头部
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     q.next = waiters, q);
            // 若有超时设置，那么处理超时获取任务结果的逻辑
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            // 【3】若没有超时设置，此时直接阻塞当前线程
            else
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    // 对应成员变量state的偏移地址
    private static final long stateOffset;
    // 对应成员变量runner的偏移地址
    private static final long runnerOffset;
    // 对应成员变量waiters的偏移地址
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
