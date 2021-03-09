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

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import com.sun.xml.internal.bind.v2.TODO;
import sun.misc.Unsafe;
// 文章推荐：
// https://www.cnblogs.com/micrari/p/6937995.html
// http://www.tianxiaobo.com/2018/05/01/AbstractQueuedSynchronizer-%E5%8E%9F%E7%90%86%E5%88%86%E6%9E%90-%E7%8B%AC%E5%8D%A0-%E5%85%B1%E4%BA%AB%E6%A8%A1%E5%BC%8F/
/**
 * 【注意】
 *  1，tryLock即使在公平模式下也是非公平的，即只要锁没有任何线程获得的情况下，此时调用tryLock的这个线程闯进来会马上获得锁，而不顾那些正在队列等待的线程；
 *  2，TODO 【Question18】tryLock(timeout)支持公平策略，但不支持非公平策略？
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h3>Usage</h3>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 *
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 *
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 *  <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer
        implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.  A
     * "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread. The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     *
     * <p>To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you just set the head field.
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * <p>Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * <p>The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * <p>We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.  Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     *
     * <p>Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * <p>CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * <p>Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */
    static final class Node {
        /** Marker to indicate a node is waiting in shared mode */
        static final Node SHARED = new Node();
        /** Marker to indicate a node is waiting in exclusive mode */
        static final Node EXCLUSIVE = null;

        /** waitStatus value to indicate thread has cancelled */
        static final int CANCELLED =  1;
        /** waitStatus value to indicate successor's thread needs unparking */
        /** SIGNAL当当前节点释放锁的时候需要唤醒后继节点，可想而知，当阻塞的时候需要将前驱节点的waitStatus置为SIGNAL */
        static final int SIGNAL    = -1;
        /** waitStatus value to indicate thread is waiting on condition */
        static final int CONDITION = -2;
        /**
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         */
        static final int PROPAGATE = -3;

        /**
         * Status field, taking on only the values:
         *   SIGNAL:     The successor of this node is (or will soon be)
         *               blocked (via park), so the current node must
         *               unpark its successor when it releases or
         *               cancels. To avoid races, acquire methods must
         *               first indicate they need a signal,
         *               then retry the atomic acquire, and then,
         *               on failure, block. 该状态设置在当前节点的前驱节点，作用于当前节点
         *   CANCELLED:  This node is cancelled due to timeout or interrupt.
         *               Nodes never leave this state. In particular,
         *               a thread with cancelled node never again blocks.该状态设置在当前节点，作用域当前节点
         *   CONDITION:  This node is currently on a condition queue.
         *               It will not be used as a sync queue node
         *               until transferred, at which time the status
         *               will be set to 0. (Use of this value here has
         *               nothing to do with the other uses of the
         *               field, but simplifies mechanics.) 该状态设置在当前节点，作用域当前节点
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.该状态只会在头节点设置，来保证传播
         *   0:          None of the above，【重要知识点】前继节点ws=0表示当前线程正在运行，没有被阻塞住，此时当前线程有以下两种情况：【1】刚进入同步队列，前继节点ws还未来得及置为SIGNAL；【2】当前线程节点刚被唤醒，此时也需要将前继节点ws设置为0
         *
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         *
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         */
        volatile int waitStatus;

        /**
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         */
        volatile Node prev;

        /**
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         */
        volatile Node next;

        /**
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         */
        volatile Thread thread;

        /**
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         */
        Node nextWaiter;

        /**
         * Returns true if node is waiting in shared mode.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     *         value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * Inserts node into queue, initializing if necessary. See picture above.
     * @param node the node to insert
     * @return node's predecessor
     */
    private Node enq(final Node node) {
        // 自旋
        for (;;) {
            // 同样，先拿到尾节点
            Node t = tail;
            // 如果尾节点为null，此时执行初始化，即新建一个Node节点作为头节点，并把tail尾节点指向head头节点
            if (t == null) { // Must initialize
                if (compareAndSetHead(new Node()))
                    tail = head;
                // 如果尾节点不为空，说明已经初始化过（注意若是首次初始化，也会经过自旋的方式进入该else分支），
                // 此时通过自旋+CAS的方式重新执行入队操作
            } else {
                node.prev = t;
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    // 注意，这里返回的是当前节点的前驱节点，而不是当前节点哈
                    return t;
                }
            }
        }
    }

    /**
     * Creates and enqueues node for current thread and given mode.
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    private Node addWaiter(Node mode) {
        // 将入队的当前线程封装为Node节点，mode为Node.EXCLUSIVE或Node.SHARED
        Node node = new Node(Thread.currentThread(), mode);
        // Try the fast path of enq; backup to full enq on failure
        // 先尝试下快速入队，若失败，则调用end(node)方法通过自旋+CAS的方式入队
        // 取得同步队列的尾节点
        Node pred = tail;
        // 若尾节点不为null，说明不是入队的第一个节点，同步队列已经初始化过，此时尝试快速入队即将当前线程节点插入到同步队列尾部
        // 若尾节点为null，说明该同步队列为空，还未入队过一个线程节点或（之前入队的全部线程节点已经出队 TODO 【QUESTION50】这种情况待确认）
        if (pred != null) {
            // 将入队的当前线程节点的prev指针指向尾节点
            // TODO 【QUESTION49】 为何在if (compareAndSetTail(pred, node))条件前面就先将当前节点的前指针指向尾节点呢？难道避免在CPU切换的时候还能从后往前遍历？
            // 下面的文字来自：http://www.tianxiaobo.com/2018/05/01/AbstractQueuedSynchronizer-%E5%8E%9F%E7%90%86%E5%88%86%E6%9E%90-%E7%8B%AC%E5%8D%A0-%E5%85%B1%E4%BA%AB%E6%A8%A1%E5%BC%8F/
            /*
             * 将节点插入队列尾部。这里是先将新节点的前驱设为尾节点，之后在尝试将新节点设为尾节
             * 点，最后再将原尾节点的后继节点指向新的尾节点。除了这种方式，我们还先设置尾节点，
             * 之后再设置前驱和后继，即：
             *
             *    if (compareAndSetTail(t, node)) {
             *        node.prev = t;
             *        t.next = node;
             *    }
             *
             * 但但如果是这样做，会导致一个问题，即短时内，队列结构会遭到破坏。考虑这种情况，
             * 某个线程在调用 compareAndSetTail(t, node)成功后，该线程被 CPU 切换了。此时
             * 设置前驱和后继的代码还没带的及执行，但尾节点指针却设置成功，导致队列结构短时内会
             * 出现如下情况：
             *
             *      +------+  prev +-----+       +-----+
             * head |      | <---- |     |       |     |  tail
             *      |      | ----> |     |       |     |
             *      +------+ next  +-----+       +-----+
             *
             * tail 节点完全脱离了队列，这样导致一些队列遍历代码出错。如果先设置
             * 前驱，在设置尾节点。及时线程被切换，队列结构短时可能如下：
             *
             *      +------+  prev +-----+ prev  +-----+
             * head |      | <---- |     | <---- |     |  tail
             *      |      | ----> |     |       |     |
             *      +------+ next  +-----+       +-----+
             *
             * 这样并不会影响从后向前遍历，不会导致遍历逻辑出错。
             *
             * 参考：
             *    https://www.cnblogs.com/micrari/p/6937995.html
             */
            node.prev = pred;
            // 将入队的当前线程节点赋值给尾节点即若CAS成功的话，入队的当前线程节点将成为尾节点；若失败，那么继续调用下面的enq(node)方法继续这样的逻辑
            if (compareAndSetTail(pred, node)) {
                // 执行到这里，说明CAS成功了，此时将原来尾节点的next指针指向当前入队的线程节点并返回当前节点
                pred.next = node;
                return node;
            }
        }
        // 执行到这里，说明有两种情况：1）同步队列还未初始化为空；2）前面的CAS操作即if (compareAndSetTail(pred, node))操作失败
        // 此时继续将当前节点入队
        enq(node);
        return node;
    }

    /**
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * @param node the node
     */
    private void setHead(Node node) {
        // 将当前节点设置为头结点
        head = node;
        // 当前节点持有的线程置为null
        node.thread = null;
        // 当前节点的prev指针置为null,这里也为isOnSynchronizeQueue方法里的if判断node.prev = null埋下了伏笔。
        node.prev = null;
        // 因为当前节点的nextWaiter指向的是Node.EXCLUDE，而Node.EXCLUDE实质就是null，因此不需要做node.nextWaiter=null的操作
        // 因为当前节点的next指针的节点可能仍需要等待，此时也不需要做node.next = null的操作
    }

    /**
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     */
    private void unparkSuccessor(Node node) {
        // TODO 【Question2】 唤醒头结点的后一个节点后，不用将这个节点移除吗？
        //      【Answer2】 答案就在acquireQueued方法，因为唤醒park的节点后，又会进入for循环，
        //                 然后进入if (p == head && tryAcquire(arg)) 分支，重新设置当前被唤醒的节点为head节点
        //                 和将之前Head节点的指向next置空帮助GC
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        int ws = node.waitStatus;
        // TODO 【QUESTION51】 这里为何要将当前节点（一般是头节点）的ws置为0？仅仅是因为release方法中有个h.waitStatus != 0的if判断？
        if (ws < 0)
            // TODO 【QUESTION52】 这里为何要CAS，而不能直接操作相应的域（node.waitStatus = 0）呢
            compareAndSetWaitStatus(node, ws, 0);

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actualf
         * non-cancelled successor.
         */
        // 拿到当前节点的下一个节点
        Node s = node.next;
        // 若下一个节点被取消了
        if (s == null || s.waitStatus > 0) {
            s = null;
            // TODO 【QUESTION50】为何这里要从尾结点开始即从后往前遍历找到第一个未被取消的线程节点？虽然下面的答案有一定的道理，但是还未解决我的疑问，因为假如不是下面这种情况呢？
            //                   如果从尾节点向前遍历，会不会存在前面很多未被取消的线程不能被唤醒？
            // 下面的文字来自：http://www.tianxiaobo.com/2018/05/01/AbstractQueuedSynchronizer-%E5%8E%9F%E7%90%86%E5%88%86%E6%9E%90-%E7%8B%AC%E5%8D%A0-%E5%85%B1%E4%BA%AB%E6%A8%A1%E5%BC%8F/
            /*
             * 这里如果 s == null 处理，是不是表明 node 是尾节点？答案是不一定。原因之前在分析
             * enq 方法时说过。这里再啰嗦一遍，新节点入队时，队列瞬时结构可能如下：
             *                      node1         node2
             *      +------+  prev +-----+ prev  +-----+
             * head |      | <---- |     | <---- |     |  tail
             *      |      | ----> |     |       |     |
             *      +------+ next  +-----+       +-----+
             *
             * node2 节点为新入队节点，此时 tail 已经指向了它，但 node1 后继引用还未设置。
             * 这里 node1 就是 node 参数，s = node1.next = null，但此时 node1 并不是尾
             * 节点。所以这里不能从前向后遍历同步队列，应该从后向前。
             */
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        // 将当前节点的下一个线程节点的线程唤醒
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
    // 【注意】这是一个并发调用的方法，在releaseShared、acquireShared都会调用该方法。
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (;;) {
            Node h = head;
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                // 如果头节点ws为SIGNAL，说明需要唤醒后继节点
                if (ws == Node.SIGNAL) {
                    // 将头节点的ws置为0，说明后继节点正在运行了（没有阻塞了）
                    // 【重要知识点】前继节点ws=0表示当前线程正在运行，没有被阻塞住，此时当前线程有以下两种情况：【1】刚进入同步队列，前继节点ws还未来得及置为SIGNAL；【2】当前线程节点刚被唤醒，此时也需要将前继节点ws设置为0，即这里的情况。
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    // 唤醒后继节点
                    unparkSuccessor(h);
                }
                // 如果此时头结点ws为0，说明其后继节点要么是刚入队的第一个节点还没来得及将头结点的ws置为SIGNAL，要么就是同步队列中的第一个线程节点刚被唤醒，此时ws也为0
                // 因此，在这种情况下，需要设置头节点的ws为PROPAGATE，以便在同步队列中的第一个线程被唤醒且该线程（或该线程是刚闯进来的线程但头节点的ws为0的情况）
                // 获取到共享的同步状态后进入setHeadAndPropagate时，此时在if分支有 head.ws < 0的判断，此时即可以保证唤醒头节点的下下个线程，否则可能无法唤醒唤醒头节点的下下个线程
                // 及其该节点后的同步队列的其他线程。对于这种情况，我再举例说明下：在并发下，有两个线程，一个是获取到共享同步状态的线程，假如此时共享的同步状态为0了，
                // 另一个是刚闯入同步队列的第一个线程，此时头节点的ws还未来的及设置为SIGNAL即头结点的ws为0.与此同时，手中拥有共享同步状态的线程此时突然调用release来释放手中的
                // 同步状态，此时恰好执行到这里，判断头结点的ws为0后什么都不做而是直接退出的话，假如此时那个刚闯进来的线程获取到共享的同步状态，此时共享的同步状态已为0，
                // 当其再调用setHeadAndPropagate方法判断if条件时，此时同步状态为0不满足propagate > 0的条件，此时ws=0又不满足ws < 0的条件，此时该闯进来的线程就无法唤醒其后继的
                // 线程节点了（假如同时又有其他并发的线程入队！）。
                // TODO 【QUESTION57】 如果这里将头结点的ws置为SIGNAL是不是也可以？因为SIGNAL也是小于0，符合setHeadAndPropagate方法判断if条件的ws < 0的条件，如果不可以会有什么后果？有空再分析这种情况
                // TODO 【QUESTION60】 这里JDK曾经有个bug，设置 Node.PROPAGATE是好像是后面才添加的，
                //                    参考：https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6801020
                // 【重要】节点的的PROPAGATE在这里设置，目的是为了解决并发释放的时候后继节点无法被唤醒的问题。注意，共享状态下的前继节点ws正常情况下也是SIGNAL哈。
                else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            // 若头节点未变，说明还是当年的那个头结点，在唤醒后继线程后未发生改变，此时负责release的线程使命已经完成，直接退出for循环即可
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        // 将当前线程节点设置为头结点。
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        // 首先这里需要明确两点：
        // 【1】在共享的同步状态仍大于0的情况下，唤醒同步队列的非第一个节点线程不是由已获取到同步状态的线程release时来唤醒的，而是由其前一节点，而已获取到同步状态的线程release时来仅仅唤醒同步队列中的第一个线程哈；
        // 【2】某个已获取到同步状态的线程release的最终结果并非唤醒所有同步队列中的线程，而是根据目前已有的同步状态数量来决定唤醒多少个，这样就减少了竞争，否则动不动就将所有同步队列的线程唤醒，此时只有一个同步状态呢？
        //     此时岂不是大量线程竞争这个仅有的一个同步状态，而导致大量无畏的竞争呢？
        // 这个propagate参数即之前tryAcquireShared方法返回的参数，此时propagate又有三种情况：
        // 1）propagate>0；2）propagate=0；3）propagate<0
        // 【1】对于1）propagate>0的情况：举个例子比如Semaphore只要获取完后还有信号量或CountDownLatch已经countDown完的情况，此时propagate>0，
        //     对于Semaphore，此时信号量大于0说明地主家还有余粮，此时需要唤醒更多同步队列中的线程去获取信号量；
        //     对于CountDownLatch，此时count已经为0了，说明所有同步队列的线程都符合条件了，自然要唤醒更多的同步队列中的线程了
        //     ==》因为这里的功能本身就是根据同步状态由多少，就唤醒多少个同步队列中的线程。

        //     或许这里大家有个疑问，这里除了判断propagate > 0外，为啥还要判断h.waitStatus < 0小于0的情况呢？
        //     这个答案已经在doReleaseShared方法中的注释中解答。
        // TODO 【QUESTION58】 (h = head) == null || h.waitStatus < 0)又是属于哪种情况呢？

        if (propagate > 0 || h == null || h.waitStatus < 0 ||
                (h = head) == null || h.waitStatus < 0) {
            // 若当前线程是同步队列中的第一个线程，被release的线程唤醒了，此时当前线程获取到同步状态，因此调用了setHeadAndPropagate方法将当前线程节点设置为头结点后，
            // 判断共享的同步状态仍大于0即propagate > 0（说明同时有其他线程release了同步状态）或waitStatus < 0 ，此时继续唤醒当前节点的下一个线程。
            Node s = node.next;
            // TODO 【QUESTION59】s == null又是属于哪种情况？
            if (s == null || s.isShared())
                // 【注意】执行到这里，说明当前节点已经变成了头节点，然后调用doReleaseShared方法中的unparkSuccessor(h)时拿到头结点的下一个节点即当前节点的下一个节点哈
                doReleaseShared();
        }
    }

    // Utilities for various versions of acquire

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;
        // 将当前节点的线程置为null
        node.thread = null;

        // Skip cancelled predecessors
        Node pred = node.prev;
        // 从后往前找到第一个未取消的节点，注意有可能找到的是头节点
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        Node predNext = pred.next;

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        // 将当前节点的ws设置为CANCELLED，注意这里并不是将取消的节点的前驱节点的ws置为CANCELLED哈，只有SIGNAL是设置在前驱节点，作用域当前节点的，而PROPAGATE只会在头节点设置。
        node.waitStatus = Node.CANCELLED;

        // If we are the tail, remove ourselves.
        // 如果当前抛出异常的线程对应节点是尾节点，那么将之前从后往前遍历找到的第一个未取消的pred线程节点设为尾节点即将当前线程节点移除
        if (node == tail && compareAndSetTail(node, pred)) {
            // 同时将之前从后往前遍历找到的第一个未取消的pred线程节点的next指针置为null
            compareAndSetNext(pred, predNext, null);
            // 如果当前抛出异常的线程对应节点不是尾节点，则说明其后的正在等待的线程节点需要被唤醒，此时又分两种情况：
            // 【1】如果从后往前遍历第一个未被取消的pred线程节点不是头节点，且其ws为SIGNAL，此时将pred的next指针指向被取消的当前线程的下一个节点，此时不用唤醒，
            //     因为前面还有正在阻塞的线程节点，还轮不到，而这个被取消的线程节点很可能是被中断唤醒的
            // 【2】如果从后往前遍历第一个未被取消的pred线程节点是头节点，那么此时需要唤醒后一个线程节点，否则后一个线程节点可能会永远阻塞等待？
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            // 如果从后往前遍历第一个未被取消的pred线程节点不是头节点，且其ws为SIGNAL，此时将pred的next指针指向被取消的当前线程的下一个节点
            // （此时这个节点是尾节点那么ws=0，如果不是，那么ws=-1）
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
                // 如果从后往前遍历第一个未被取消的pred线程节点是头节点，那么此时需要唤醒后一个线程节点，这个要唤醒的后一个线程节点即当前被取消的线程节点的下一个节点
            } else {
                // 唤醒node节点的后续节点，个人感觉这里是因为本来要被唤醒的当前节点被取消，此时所以需要唤醒下一个未被取消的节点，因为此时锁很可能被释放，再不唤醒同步队列阻塞的线程可能就没有其他线程来唤醒了
                // 下面文字来源：http://www.tianxiaobo.com/2018/05/01/AbstractQueuedSynchronizer-%E5%8E%9F%E7%90%86%E5%88%86%E6%9E%90-%E7%8B%AC%E5%8D%A0-%E5%85%B1%E4%BA%AB%E6%A8%A1%E5%BC%8F/
                /* TODO 【QUESTION54】感觉不存在下面这种情况，因为node2入队调用shouldParkAfterFailedAcquire方法时，会将ws>0的前一节点移除，直接插入到head节点后面，然后再自旋设置head节点的ws为-1.
                 * 唤醒后继节点对应的线程。这里简单讲一下为什么要唤醒后继线程，考虑下面一种情况：
                 *        head          node1         node2         tail
                 *        ws=0          ws=1          ws=-1         ws=0
                 *      +------+  prev +-----+  prev +-----+  prev +-----+
                 *      |      | <---- |     | <---- |     | <---- |     |
                 *      |      | ----> |     | ----> |     | ----> |     |
                 *      +------+  next +-----+  next +-----+  next +-----+
                 *
                 * 头结点初始状态为 0，node1、node2 和 tail 节点依次入队。node1 自旋过程中调用
                 * tryAcquire 出现异常，进入 cancelAcquire。head 节点此时等待状态仍然是 0，它
                 * 会认为后继节点还在运行中，所它在释放同步状态后，不会去唤醒后继等待状态为非取消的
                 * 节点 node2。如果 node1 再不唤醒 node2 的线程，该线程面临无法被唤醒的情况。此
                 * 时，整个同步队列就回全部阻塞住。
                 */
                unparkSuccessor(node);
            }
            // TODO 【QUESTION53】help gc 为何不是将node置为null呢？
            node.next = node; // help GCf
        }
    }

    /**
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        // 执行到这里，有三种情况：
        // 【1】如果同步队列初始化时进入的第一个节点线程经过再次的“投胎”机会仍未获取到同步状态,此时pred即head节点，所以刚开始pred.waitStatus=0,后面才被置为SIGNAL(-1)
        // 【2】如果当前线程节点的前一个节点不是head节点，说明前面还有等待的线程，所以pred即前面等待的线程节点，此时刚开始pred.waitStatus=0，后面才被置为SIGNAL(-1)
        // 【总结】尾节点的ws一开始总是0，后面才被置为-1，然后for循环再次进入该方法返回true将该线程节点阻塞
        // 【3】已经处在同步队列中的第一个节点线程被唤醒后，会经过自旋，会再次获取锁，但仍未获取到同步状态，此时pred即head节点，所以刚开始pred.waitStatus=-1即SIGNAL
        int ws = pred.waitStatus;
        // 1) pred.waitStatus == -1
        // 【1.2】在【1.1】步设置head节点pred的ws为SIGNAL后，退出shouldParkAfterFailedAcquire并返回false，
        //        然后该线程节点仍未获取到同步状态此时再次进入该方法时，此时head节点的ws就为SIGNAL,因此返回true，让该线程节点park阻塞即可
        if (ws == Node.SIGNAL)
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true;
        // 2) pred.waitStatus > 0 即 pred.waitStatus == Node.CANCELLED(1)
        // 若pred线程节点被取消，此时从pred节点开始向前遍历找到未取消的节点并把当前节点查到该未取消的节点后面
        if (ws > 0) {
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
            // 3) pred.waitStatus == 0 or Node.PROPAGATE(-3),TODO 注意为何这里肯定不是Node.CONDITION（-2）
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             */
            // 【1.1】这个步骤接前面【1】的情况，此时pred是head节点，此时将head节点pred的ws置为SIGNAL状态，说明下一个节点需要阻塞等待被SIGNAL
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */
    private final boolean parkAndCheckInterrupt() {
        // 最终将未能获取到同步状态的线程阻塞住
        LockSupport.park(this);
        // 执行到这里，说明正在park阻塞的线程被unpark（正常release唤醒）或被中断了
        // 因此调用Thread.interrupted()区分该parking的线程是被正常唤醒还是被中断，若被中断，中断标识将被清除
        return Thread.interrupted();
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    final boolean acquireQueued(final Node node, int arg) {
        // 能进入到该方法，说明该线程节点已经进入同步队列了，但是还未park阻塞
        // failed失败标志：若抛出异常，那么该值保持不变为true
        boolean failed = true;
        try {
            // 中断标志
            boolean interrupted = false;
            // 自旋
            // 【重要知识点】只要进入acquireQueued方法的线程，在正常执行没异常的情况下，该线程是必定要获取到同步状态（锁）才能退出for循环即该方法结束，除非在tryAcquire时抛出异常。
            for (;;) {
                // 拿到当前节点的前一个节点
                final Node p = node.predecessor();
                // 如果当前线程节点的前一个节点是head节点，那么在此调用tryAcquire方法看能否再次获取到同步状态，基于下面两种情况考虑：
                // 【1】因为在当前线程在进入同步队列的时候说不定同步状态(锁)已经被别的线程释放，这里再作一次努力，
                //      而不是简单粗暴的将该线程park阻塞住哈，因此再给当前线程节点（即同步队列第一个线程节点）一次“投胎”的机会；
                //      其实是两次，还有一次就是调用shouldParkAfterFailedAcquire方法将头节点的ws设置为SIGNAL后返回false，此时自旋再次执行if分支
                // 【2】如果正在同步队列park阻塞的第一个线程被唤醒后，也会进入该if分支去尝试获取锁
                // 这里判断当前线程的前一节点是否为头结点还有一个好处就是加入同步队列中的非第一个线程节点被中断唤醒后，判断到其前一线程节点不是头节点而是同样正在等待的线程节点，
                // 此时该线程会继续进入等待状态，否则会破坏同步队列的链表结构导致该线程节点前面正在等待的线程永远无法被唤醒了。
                if (p == head && tryAcquire(arg)) {
                    // 很幸运，当前线程虽然已经进入同步队列了，但由于同时别的线程释放了同步状态（锁），因此当前线程又再一次获得了同步状态
                    // 此时将当前节点设置为头结点
                    setHead(node);
                    // 原先head头结点p的next指针指向了当前节点，因此node节点被唤醒后，这里肯定要将next指针置空，从而让原先的头结点点让其无任何引用可达从而GC TODO 【QUESTION63】若头结点没有任何其他引用了，但头结点还持有其他引用，此时头节点可以被gc吗？
                    // 【注意】此时是当前节点变成了头结点，原来的头节点要被GC
                    p.next = null; // help GC
                    // 标志failed为false即获取同步状态成功
                    failed = false;
                    // 返回中断标志，默认是false
                    return interrupted;
                }
                // 执行到这里，有三种情况：
                // 【1】如果同步队列初始化时进入的第一个节点线程经过再次的“投胎”机会仍未获取到同步状态；
                // 【2】如果当前线程节点的前一个节点不是head节点，说明前面还有等待的线程，此时当前线程节点要么不是第一个进入同步队列的要么就是处在同步队列中的线程节点被中断醒来的// 【3】已经处在同步队列中的第一个节点线程被唤醒后0，会经过自旋，会再次获取锁，但仍未获取到同步状态。
                //  基于以上三种情况，此时获取同步状态失败后的线程节点需要将自己park阻塞住
                // 【QUESTION1】假如park的线程被唤醒后，acquireQueued的执行逻辑是怎样的？
                // 【ANSWER1】1）如果唤醒的是同步队列中的第一个线程节点，此时其前节点就是head头节点，因此再次进入for循环去获取同步状态；
                //           2）如果唤醒的不是同步队列中的第一个线程节点（可能是被中断唤醒的），此时会继续park阻塞。
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    // 执行到这里，说明正在parking阻塞的线程被中断从而被唤醒，因此设置interrupted为true，因为parkAndCheckInterrupt将中断标识清除了，所以后续该线程需要自我interrupt一下
                    interrupted = true;
            }
        } finally {
            // 若failed仍为true的话，说明前面try代码段抛出了一个异常,这里很可能是tryAcquire方法抛出异常，因为tryAcquire方法是留给子类实现的难免有异常
            if (failed)
                // 抛出异常后，需要取消获取同步状态同时将已经入队的当前线程节点移除，根据情况看是否需要唤醒下一个线程节点，当然，异常会继续向上抛出
                // 假如前面是tryAcquire抛出异常，此时有以下两种情况：
                // 1)获取到了同步状态（锁）后抛出异常，在业务代码的finally块中执行释放同步状态，此时无异常；
                // 2）还未获取到同步状态就抛出异常，在业务代码的finally块中执行释放同步状态，此时释放同步状态的方法tryReleae的结果会为负值，同时本异常继续向上抛出；
                // 此时也不会唤醒后继节点，若又不符合cancelAcquire方法唤醒后继节点的条件，难道此时后继节点就只有等到有其他新来的线程再次获取同步状态后release来唤醒么？假如没有新来的线程呢？ TODO 待确认是不是存在这种情况？
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            // 能执行到这里，说明前面try代码段抛出了一个异常，比如InterruptedException
            if (failed)
                // 此时取消当前节点，满足条件则唤醒后续节点
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared uninterruptible mode.
     * @param arg the acquire argument
     */
    // 这个doAcquireShared方法的逻辑跟acquireQueued方法逻辑差不多，最大的区别就是唤醒的后继节点在满足条件的情况下，需要继续唤醒其后的节点
    private void doAcquireShared(int arg) {
        // 将当前线程以共享节点类型入同步队列，Node.SHARED保存在Node节点的nextWaiter这个属性里
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                // 获取到当前节点的前继节点
                final Node p = node.predecessor();
                // 若前继节点是头节点，说明自己是同步队列中的第一个线程节点，不管是新入队的还是被唤醒的；
                // 【1】如果当前节点是新入队的第一个线程节点，若还没调用shouldParkAfterFailedAcquire方法，此时head头节点的ws为0；若已经调用shouldParkAfterFailedAcquire方法，那么此时head头节点的ws为-1；
                // 【2】如果当前节点是被唤醒的同步队列的第一个线程节点，此时head头节点的ws为-1。
                if (p == head) {
                    // 那么此时再次尝试调用tryAcquireShared看能否获取共享的同步状态，若成功，则返回的r>=0；否则r<0；
                    int r = tryAcquireShared(arg);
                    // 若获取共享状态成功，此时需要重新设置头节点，并保证传播
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared interruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireSharedInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */
    public final void acquire(int arg) {
        // 【1】首先调用子类重写的tryAcquire方法看能否获取到同步状态（可以理解为锁），若获取到同步状态则马上返回；否则该线程被封装为Node节点进入同步队列
        if (!tryAcquire(arg) &&
                // 【3】 将当前线程 TODO 继续完善注释
                acquireQueued(
                        // 【2】执行到这里，说明该线程没能获取到同步状态，此时现将该线程进入同步队列，此时只是单纯的进入了同步队列的链表，还未park阻塞哈
                        addWaiter(Node.EXCLUSIVE), arg))
            // 执行到这里，说明当前线程在同步队列中曾经被interrupt了，此时需要自我interrupt下，搞个中断标识
            selfInterrupt();
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
                doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        // 【1】首先通过tryRelease释放同步状态，注意tryXxx方法是留给子类实现的
        if (tryRelease(arg)) {
            // 【2】若成功释放同步状态，那么将唤醒head头节点的下一个节点
            Node h = head;
            // 下面的文字来自：http://www.tianxiaobo.com/2018/05/01/AbstractQueuedSynchronizer-%E5%8E%9F%E7%90%86%E5%88%86%E6%9E%90-%E7%8B%AC%E5%8D%A0-%E5%85%B1%E4%BA%AB%E6%A8%A1%E5%BC%8F/
            /*
             * 这里简单列举条件分支的可能性，如下：
             * 1. head = null
             *     head 还未初始化。初始情况下，head = null，当第一个节点入队后，head 会被初始
             *     为一个虚拟（dummy）节点。这里，如果还没节点入队就调用 release 释放同步状态，
             *     就会出现 h = null 的情况。
             *
             * 2. head != null && waitStatus = 0
             *     表明后继节点对应的线程仍在运行中，不需要唤醒
             *
             * 3. head != null && waitStatus < 0
             *     后继节点对应的线程可能被阻塞了，需要唤醒
             */
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     */
    public final void acquireShared(int arg) {
        // 这里tryAcquireShared将返回一个int值，有三种情况：
        // 【1】如果返回值小于0，说明获取到共享的同步状态失败；
        // 【2】如果返回值等于0，说明获取共享的同步状态成功，但后续的线程将获取共享的同步状态失败；
        // 【3】如果返回值大于0，说明获取共享的同步状态成功，后续的线程依然能获取到共享的同步状态直到返回值为0
        if (tryAcquireShared(arg) < 0)
            // 执行到这里，说明当前线程获取到共享的同步状态失败，此时可能需要进入同步队列park阻塞等待
            doAcquireShared(arg);
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     * @param arg the acquire argument.
     * This value is conveyed to {@link #tryAcquireShared} but is
     * otherwise uninterpreted and can represent anything
     * you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquireShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
                doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryReleaseShared} but is otherwise uninterpreted
     *        and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    public final boolean releaseShared(int arg) {
        // 尝试释放手中的共享同步状态，对于CountDownLatch的话，当count减为0的时候返回true，对于Semaphore的话，只要能将手中的信号量释放即返回true。
        if (tryReleaseShared(arg)) {
            // 成功释放了手中的共享同步状态，此时需要唤醒后继节点，然后再由唤醒的后继线程节点负责唤醒其后继节点，而不是由成功释放共享同步状态的
            // 当前线程负责唤醒同步队列中的所有线程节点后再返回【这是值得注意的地方】
            doReleaseShared();
            // 唤醒同步队列中的第一个线程节点后，当前线程立即返回true给调用方
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     *         {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
                s.prev == head && (st = s.thread) != null) ||
                ((h = head) != null && (s = h.next) != null &&
                        s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
                (s = h.next)  != null &&
                !s.isShared()         &&
                s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     *  <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     *  <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     *         current thread, and {@code false} if the current thread
     *         is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
                ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    final boolean isOnSyncQueue(Node node) {
        // 【1】若新入条件队列的节点此时ws为CONDITION，因此此时该节点不在同步队列了
        // 【2】如果同步队列中的一个线程获取到同步状态退出同步队列的时候在调用setHead方法时，会将node.prev置为null，因此node.prev == null也标志着该节点已经不在同步队列了
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        // 【QUESTION64】这里不是很理解？为何这里只要node.next!=null就可以断定其一定在同步队列呢？因为如果同步队列中的一个线程获取到同步状态退出同步队列的时候在调用setHead方法后，
        //              此时node.next依旧不为null，但其已经出了同步队列了。
        // 【ANSWER64】 这里为何可以这么断定，玄机就在前面的if判断node.prev == null，因为如果属于QUESTION64的这种情况，就不会执行到这里了。因此执行到这里的话，该节点一定在同步队列中，大写的妙啊！
        // 【重要】节点的next和prev指针是同步队列专属的，而节点的nextWaiter指针是条件队列专属的哈，注意区分。
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        // 执行到这里，说明此时node.next == null，说明在enq方法设置尾节点时肯能CAS失败且同时又遇到了CPU切换，因此符合这种情况，但此时node.prev是不为null的哈
        // 如果前面的if判断都不满足条件，此时执行到这里，从尾部开始寻找当前节点是否在同步队列中，至于为什么从尾部开始，因为其prev指针一定不为null，更多详情解释详见enq方法。
        return findNodeFromTail(node);
    }

    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    final boolean transferForSignal(Node node) {
        /*
         * If cannot change waitStatus, the node has been cancelled.
         */
        // 1）正常情况下，条件队列中的节点ws为CONDITION，因此能成功将该节点的ws置为0,因此CAS成功，继续往下执行
        // 2）否则，若条件队列的节点的ws不为CONDITION，说明该节点被取消，因此CAS失败，返回false
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */
        // 执行到这里，说明前面CAS成功将当前节点的ws置为0
        // 此时将从条件队列出来的节点插入到同步队列尾部并返回当前节点的前驱节点（该前驱节点是该节点插入到同步队列前的尾节点）
        Node p = enq(node);
        //  拿到该前驱节点的ws
        int ws = p.waitStatus;
        // 1）若前驱节点ws>0，说明前驱节点被取消了，需要唤醒当前节点的线程；
        // 2）若前驱节点ws<=0，此时前驱节点ws为0，因为前驱节点原先是当前节点入同步队列前的尾节点，同步队列尾节点ws=0，此时CAS前驱节点ws为SIGNAL即表示当前节点会被等待唤醒，此时直接返回true；若CAS失败，则马上唤醒当前节点 TODO 【QUESTION64】 为何这里会CAS失败呢？
        // TODO 【QUESTION65】为何前驱节点被取消或compareAndSetWaitStatus(p, ws, Node.SIGNAL)失败需要马上唤醒当前节点？
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            // 注意这里被唤醒的线程和从条件队列的节点转移到同步队列的节点被唤醒后，会在原来await方法的park那句代码醒过来，然后调用下面的acquireQueue方法
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    // 如果该线程节点在signal前被中断，此时返回true，因为await方法是不可中断的？
    final boolean transferAfterCancelledWait(Node node) {
        // 若能成功更新状态为CONDITION的当前线程节点为0，说明该线程在条件队列中被中断醒来
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            // TODO 待分析，这里为啥要将该节点放入同步队列呢？
            enq(node);
            return true;
        }
        // 代码执行到这里，说明前面CAS节点的ws失败，说明该线程是在被signal过程中或被signal后（被unpark后）被中断的
        // 为什么呢？因为若该线程还没被signal即在signal前，该节点ws为CONDITION
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        // 若该线程节点还没能成功从条件队列转移到同步队列，说明是正在转移的过程中，此时什么也不用做，只是自旋并让出cpu时间片段直到该节点被转移到同步队列后退出自旋并返回false
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     * @param node the condition node for this wait
     * @return previous sync state
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            // 拿到当前节点的同步状态，可以看到这里对于可重入锁的情况，不管持有的同步状态是是多少，此时统一释放
            int savedState = getState();
            // 调用release方法释放同步状态并唤醒后继的线程节点，若成功，则返回当前节点的同步状态，另有他用
            if (release(savedState)) {
                failed = false;
                return savedState;
                // 若释放同步状态失败了，那么抛出IllegalMonitorStateException
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            // 若此时failed仍为true，那么大可能是调用release方法中的tryRelease抛出了异常，此时需要将当前节点的ws置为CANCELLED以便该节点后续能被移除掉（因为已经废了 TODO 【QUESTION61】这种情况该线程抛出异常就退出了，但同步状态（锁）仍不为0即线程异常退出，但锁未释放，此时该怎样处理？），同时异常继续往上抛出。
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *         is not held
     * @throws IllegalArgumentException if the given condition is
     *         not associated with this synchronizer
     * @throws NullPointerException if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** First node of condition queue. */
        private transient Node firstWaiter;
        /** Last node of condition queue. */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        // Internal methods

        /**
         * Adds a new waiter to wait queue.
         * @return its new wait node
         */
        private Node addConditionWaiter() {
            // 拿到条件队列的尾节点
            Node t = lastWaiter;
            // TODO 异常流程待分析
            // If lastWaiter is cancelled, clean out.
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            // 将当前线程封装为一个Node节点，此时当前节点的ws状态为CONDITION（-2）
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            // 若尾节点为null，说明还未初始化过，此时将尾节点指针指向当前节点即可
            if (t == null)
                firstWaiter = node;
                // 若已经初始化过，此时条件队列已经有等待的节点的了，此时将当前节点加入到条件队列尾部即可
            else
                t.nextWaiter = node;
            // 并将尾节点指针移动到当前节点
            lastWaiter = node;
            // 返回新加入条件队列的当前节点
            return node;
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do {
                // 拿到条件队列中第一个节点的下一个节点，若下一个节点为null，说明条件队列除了这个节点外没有其他节点，此时将lastWaiter指针指向null，
                // 因为该节点即将要从条件队列出队转移到同步队列中
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                // 将当前节点的nextWaiter置为null即完成了将该节点从条件队列中移除的动作，同时也为await方法中的if (node.nextWaiter != null) 判断埋下了伏笔
                //
                first.nextWaiter = null;
                // 1）若transferForSignal返回true，说明要么条件队列出队的当前节点被成功转移到了同步队列，此时直接退出while循环；
                // 2）若transferForSignal返回false,说明条件队列中的该节点被取消，此时继续遍历条件队列的下一个节点，若下一个节点不为null，那么继续将下一个节点转移到同步队列中
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        /**
         * Removes and transfers all nodes.
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;
                    if (trail == null)
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    if (next == null)
                        lastWaiter = trail;
                }
                else
                    trail = t;
                t = next;
            }
        }

        // public methods

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signal() {
            // 对于Lock类实现来说，如果持有同步状态（锁）的线程不是当前线程，则抛出IllegalMonitorStateException
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 拿到条件队列的第一个节点
            Node first = firstWaiter;
            if (first != null)
                // 将该节点唤醒
                doSignal(first);
            // signal完后，该线程会释放同步状态（锁），然后从条件队列中被转移到同步队列的节点将被唤醒来竞争同步状态（锁），前提是此时该节点已经是同步队列的第一个节点哈
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /** Mode meaning to reinterrupt on exit from wait */
        private static final int REINTERRUPT =  1;
        /** Mode meaning to throw InterruptedException on exit from wait */
        private static final int THROW_IE    = -1;

        /**
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                    // 若被中断了，此时调用transferAfterCancelledWait方法来确定下该线程中断是在被signal前还是被signal后中断，
                    // 【1】若该线程是在被signal前中断了，说明该线程还处于条件队列中就被中断了，此时transferAfterCancelledWait方法返回，需要将该异常抛出去
                    // 【2】若该线程是在被signal中或后被中断了，说明这个线程是正常唤醒的线程，又因为前面调用了Thread.interrupted() 清除了中断标识，此时返回REINTERRUPT
                    //     即意味着返回await方法后需要自我interrupt下，搞个中断标志供用户识别，用户怎么处理是用户的事情了
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final void await() throws InterruptedException {
            // 能执行到这里，说明当前线程已经持有了锁，当前线程是不在同步队列的哈
            // 首先检查当前线程是否被中断，如果是，那么抛出InterruptedException
            if (Thread.interrupted())
                throw new InterruptedException();
            // 将当前线程加入到条件队列中，此时还未park阻塞且还未放弃锁,注意条件队列是一个单链表结构
            Node node = addConditionWaiter();
            // 释放同步状态并唤醒后继的线程节点，若成功，则返回当前节点的同步状态;若抛出异常，下面的逻辑不用执行了，此时同步状态未被释放？
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            // 如果当前节点不在同步队列中，则直接阻塞当前已经释放同步状态（释放了锁）的线程
            // 不在同步队列中有以下情形：【1】当一个线程获取到同步状态（锁）后，马上调用了await方法，此时当前线程会被封装为一个CONDITION节点并进入条件队列，此时ws = CONDITION；
            //                       【2】当一个在条件队列阻塞的线程节点被唤醒或被中断后，若此时未能成功转移到同步队列？
            //                           TODO 【QUESTION66】 确认下有没有这种可能？感觉没有，因为如果是该线程在parking被中断唤醒，会调用checkInterruptWhileWaiting方法里的enq将该节点入队，之后再到while循环条件；
            //                                              如果该线程被正常唤醒的话，也是先将该线程节点先入同步队列，最后再唤醒该parking的线程，然后再到while循环条件
            while (!isOnSyncQueue(node)) {
                // 这里首先需要明确两点：1）被正常signal唤醒的线程需要从条件队列进入同步队列；2）正在条件队列中阻塞的线程被中断的话，最终也是需要进入同步队列；
                // 因为基于上面两种情况，只要醒过来的线程都要去重新竞争同步状态（锁），而竞争同步状态（锁）的正常步骤都是先将该线程节点入同步队列，然后再再调用
                // acquireQueued方法自旋获取锁，这也从侧面解释了为啥条件队列被中断的线程节点也需要进入同步队列。此外，基于2）的情形，如果不进入同步队列即不调用acquireQueued（调用了acquireQueued
                // 方法意味着正常情况下该线程必须先获取到同步状态（锁）才能返回即正常情况下，只要调用了acquireQueued就意味着最终能获取到锁）
                // 而最后在业务代码块中释放了同步状态（锁），此时是没获取到同步状态（锁）的，此时肯定会有另一个异常（没获取同步状态却释放同步状态的异常）会抛出从而覆盖中断异常

                // 1）若是条件队列中阻塞（还未被signal）被中断的线程醒来后，此时是THROW_IE的情形，同时调用acquireQueued方法又获取到了同步状态（锁），此时该线程节点会退出同步队列，
                //    最后在reportInterruptAfterWait方法中抛出InterruptedException，如果该线程抛出异常后，是不是刚获取的同步状态（锁）没有释放，所以要求我们在finally块中执行释放同步状态（锁）的操作来确保异常也能成功释放同步状态。
                // 2）若是条件队列中阻塞（还未被signal）被中断的线程醒来后，此时是THROW_IE的情形，同时调用acquireQueued方法没能获取到同步状态（锁），此时该线程节点会继续留在同步队列，并且再次
                //    进入parking阻塞状态。若在同步队列中parking没有被中断，当被唤醒后，若能获取到同步状态，此时acquireQueued方法返回false，继续执行代码时，因为interruptMode=-1，此时继续执行
                //    reportInterruptAfterWait方法；当若在同步队列中parking中被中断，当被唤醒后，若能获取到同步状态，此时acquireQueued方法返回true。
                // 【总结】正在条件队列中parking的线程不管是被正常signal唤醒还是异常中断唤醒，此时都需要入同步队列去竞争锁，以避免业务代码的finally块释放同步状态出错。
                LockSupport.park(this);
                // 检查下该线程在等待过程中有无被中断，若是被中断唤醒，此时直接退出while循环；若是正常被signal唤醒，此时继续while循环，此时若被signal唤醒，执行到这里正常情况下该节点已经被转移到同步队列了
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            // 如果当前线程已经在同步队列中，可能是当前线程被唤醒且被转移到同步队列了，此时需要再次去获取同步状态（锁），因为另一个调用signal唤醒的线程之后会释放锁。
            // 【注意】从条件队列转移到同步队列的阻塞节点被唤醒后，将执行这里的逻辑即调用acquireQueued去竞争锁哈！
            // 这里的savedState保存的是之前fullyRelease的返回值，考虑可重入锁的情况，之前释放了多少同步状态，此时再次获取同步状态时就同样要再次获取同等量级的同步状态。
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                // 这里表示条件队列的线程节点被signal后中断，此时这里是REINTERRUPT类型，意味着需要自我interrupt下，搞个中断标志供用户识别，用户怎么处理是用户的事情了
                interruptMode = REINTERRUPT;
            // 代码执行到这里，说明该线程醒来后（不管中断还是正常唤醒），再次获取到了锁
            // 【QUESTION63】为啥node.nextWaiter != null说明该节点就被CANCEL了呢？
            // 【ANSWER63】首先这里得先明确节点何时会被CANCEL,一般正在同步队列或条件队列parking阻塞的节点若被中断的话，此时意味着该节点被CANCEL了 TODO 总结下还有无其他节点被CANCEL的情况。
            //            其次，线程能执行到这里，说明要么正在条件队列的线程节点要么被signal正常唤醒，要么被中断，下面就这两种情况展开分析：
            //            1)被signal正常唤醒，那么在doSignal方法中会将node.nextWaiter置为null，然后将该节点转移到同步队列最后再唤醒该节点，因此该节点被唤醒后执行到这里，不满足node.nextWaiter != null条件
            //            2）被中断唤醒，前面会通过break跳出while循环，此时满足ode.nextWaiter != null的条件，说明此时该节点被CANCEL，
            if (node.nextWaiter != null) // clean up if cancelled
                // TODO 待分析
                unlinkCancelledWaiters();
            // nterruptMode != 0，说明正在条件队列parking的线程被中断了；否则就是被正常唤醒
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *         returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
