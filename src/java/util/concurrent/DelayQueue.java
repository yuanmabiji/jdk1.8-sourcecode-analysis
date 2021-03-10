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
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;

/**
 * An unbounded {@linkplain BlockingQueue blocking queue} of
 * {@code Delayed} elements, in which an element can only be taken
 * when its delay has expired.  The <em>head</em> of the queue is that
 * {@code Delayed} element whose delay expired furthest in the
 * past.  If no delay has expired there is no head and {@code poll}
 * will return {@code null}. Expiration occurs when an element's
 * {@code getDelay(TimeUnit.NANOSECONDS)} method returns a value less
 * than or equal to zero.  Even though unexpired elements cannot be
 * removed using {@code take} or {@code poll}, they are otherwise
 * treated as normal elements. For example, the {@code size} method
 * returns the count of both expired and unexpired elements.
 * This queue does not permit null elements.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.  The Iterator provided in method {@link
 * #iterator()} is <em>not</em> guaranteed to traverse the elements of
 * the DelayQueue in any particular order.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */
public class DelayQueue<E extends Delayed> extends AbstractQueue<E>
    implements BlockingQueue<E> {

    private final transient ReentrantLock lock = new ReentrantLock();
    private final PriorityQueue<E> q = new PriorityQueue<E>();

    /**
     * Thread designated to wait for the element at the head of
     * the queue.  This variant of the Leader-Follower pattern
     * (http://www.cs.wustl.edu/~schmidt/POSA/POSA2/) serves to
     * minimize unnecessary timed waiting.  When a thread becomes
     * the leader, it waits only for the next delay to elapse, but
     * other threads await indefinitely.  The leader thread must
     * signal some other thread before returning from take() or
     * poll(...), unless some other thread becomes leader in the
     * interim.  Whenever the head of the queue is replaced with
     * an element with an earlier expiration time, the leader
     * field is invalidated by being reset to null, and some
     * waiting thread, but not necessarily the current leader, is
     * signalled.  So waiting threads must be prepared to acquire
     * and lose leadership while waiting.
     */
    private Thread leader = null;

    /**
     * Condition signalled when a newer element becomes available
     * at the head of the queue or a new thread may need to
     * become leader.
     */
    private final Condition available = lock.newCondition();

    /**
     * Creates a new {@code DelayQueue} that is initially empty.
     */
    public DelayQueue() {}

    /**
     * Creates a {@code DelayQueue} initially containing the elements of the
     * given collection of {@link Delayed} instances.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public DelayQueue(Collection<? extends E> c) {
        this.addAll(c);
    }

    /**
     * Inserts the specified element into this delay queue.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        return offer(e);
    }

    /**
     * Inserts the specified element into this delay queue.
     *
     * @param e the element to add
     * @return {@code true}
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        // 保证线程安全
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 将实现了Delayed的元素对象放入延迟队列，这个元素对象必然实现了getDelay和compareTo方法
            // 1）getDelay方法返回剩余时间，因此需要实现Delayed接口的元素对象定义一个成员变量deadLineTime来保存这个元素的截止时间，
            // 可以在构建该对象时由生成的当前时间加上delay时间即可，然后在调用getDelay方法时由deadLineTime减去当前时间，若小于等于0则说明延迟时间已到；大于0说明还未到延迟时间；
            // 2）compareTo是Comparable的方法，因为Delayed接口实现了Comparable接口，这个接口的作用是当前元素入延迟队列寻找位置使用，使得延迟时间最小的那个元素总能排到延迟队列的最前面
            q.offer(e);
            // 如果当前入队的元素占据到了延迟队列的队首位置，此时需要马上唤醒条件队列中等待的第一个线程，并将leader置空
            // 有以下两种情形能让当前入队的元素占据到延迟队列对首元素：
            // 1）当前延迟队列为空；2）当前延迟队列不为空，但是新入队的元素延迟级更高（延迟时间更短）
            // 当新入队元素占据到了延迟队列的队首时，此时需要将一个因take而阻塞的线程唤醒，这里为什么不每进一个元素就进行唤醒一次呢？
            // 你想想，如果当前进入延迟队列的元素没有占据到队首位置，说明这个入队元素的延迟时间比目前队首元素的延迟时间还要大，此时若进行唤醒take阻塞的线程，
            // 该线程醒来后很大概率会再次进入阻塞，引起没必要的线程切换，损耗性能，因为该take线程当阻塞等到剩余延迟时间到来自然会醒来。

            // 而为什么该入队元素占据到了队首就要进行唤醒阻塞的take线程呢？
            // 有两种情形需要考虑：
            // 1）此时该入队元素是第一个元素，此时不进行唤醒的话，阻塞的take线程会永远阻塞下去，然后阻塞的take线程被唤醒后再次自旋拿到队首元素，此时执行逻辑跟2）一致；
            // 2）此时延迟队列不为空，说明新入队的元素占据到了队首位置，因为阻塞的take线程此时阻塞的是原来队首元素的剩余延迟时间，
            //    此时需要唤醒阻塞的take线程重新获取新的队首元素并计算延迟时间来决定是否需要阻塞，若需要则重新阻塞剩余的延迟时间。
            if (q.peek() == e) {
                // TODO 【待分析，感觉这里分析有问题】这里将leader置为null，其中一个考虑是为了能让当延迟队列中没元素时正在阻塞的take线程被唤醒后能执行到自旋的if(leader == null)分支，然后将该线程加入到条件队列的队尾，
                // 同时leader指针指向了条件队列队尾的这个线程节点？
                leader = null;
                available.signal();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts the specified element into this delay queue. As the queue is
     * unbounded this method will never block.
     *
     * @param e the element to add
     * @throws NullPointerException {@inheritDoc}
     */
    // TODO 【QUESTION67】为何BlockingQueue的该接口有抛出中断异常，但该实现类却没抛出？
    public void put(E e) {
        offer(e);
    }

    /**
     * Inserts the specified element into this delay queue. As the queue is
     * unbounded this method will never block.
     *
     * @param e the element to add
     * @param timeout This parameter is ignored as the method never blocks
     * @param unit This parameter is ignored as the method never blocks
     * @return {@code true}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        return offer(e);
    }

    /**
     * Retrieves and removes the head of this queue, or returns {@code null}
     * if this queue has no elements with an expired delay.
     *
     * @return the head of this queue, or {@code null} if this
     *         queue has no elements with an expired delay
     */
    // 若延迟队列还没过期的元素，直接返回null，注意这里如果延迟队列没有元素也会返回null，此时需要调用size相关api来结合判断，该方法不会阻塞
    // 若延迟队列有到期的元素，此时直接返回该元素即可
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 拿到第一个元素
            E first = q.peek();
            // 说明队列空或延迟队列有元素但没有到期的元素
            if (first == null || first.getDelay(NANOSECONDS) > 0)
                return null;
            // 延时队列有已经到期的元素，直接出队处理即可
            else
                return q.poll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element with an expired delay is available on this queue.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    // 如果延迟队列为空或有元素但元素延迟还未到期，此时会阻塞直接等到延迟队列的元素延迟到期
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        // 首先获取到锁，保证线程安全，这是一个可中断的锁，此时其他线程不能再往这个队列put元素了
        lock.lockInterruptibly();
        try {
            // 自旋
            for (;;) {
                // 拿到延迟队列第一个元素
                E first = q.peek();
                // 若没有元素，直接阻塞
                if (first == null)
                    available.await();
                // 若有元素，判断队首元素延期有无到期了，此时不用担心会有其他线程放一个比该队首元素优先级更高的元素进来，因为其他线程被锁在门外
                else {
                    // 拿到队首元素的延迟还剩多少时间
                    long delay = first.getDelay(NANOSECONDS);
                    // 队首元素延迟到期了，此时直接出队
                    if (delay <= 0)
                        return q.poll();
                    // 此时队首元素延迟还未到期，此时应该将该线程阻塞等待，所以释放first引用
                    first = null; // don't retain ref while waiting
                    // 若leader线程不为空，说明已经有线程在等待队首元素啦，说明该线程也需要继续等待，因此直接阻塞该线程即可
                    if (leader != null)
                        available.await();
                    // 若leader为空，说明该线程是第一个要拿队首元素的线程，当且仅当是第一个线程才会执行到这里，否则其他线程会进入前面的if分支
                    else {
                        // 将当前线程赋值给leader
                        Thread thisThread = Thread.currentThread();
                        // leader总是指向条件队列中的第一个等待节点的线程
                        leader = thisThread;
                        try {
                            // 然后根据队首元素的剩余延迟时间来决定阻塞多久，能执行到这里的线程都会占据条件队列的第一个位置哈
                            available.awaitNanos(delay);
                        } finally {
                            // 代码执行到这里，有以下情况：
                            // 1）阻塞的take线程延迟时间到了此时醒过来，符合当前线程就是leader线程，此时该线程需要退出条件队列，因此将leader置为null同时作为一个标记（在finally块中有用到），但别忘了，此时条件队列中leader后面可能还有其他线程在等待哈；
                            //    然后该线程继续自旋执行到前面的if (delay <= 0)分支，将队首元素出队，最后在后面的finally块中判断到该标记leader==null，此时又要根据延迟队列中还有无元素来决定是否唤醒仍在条件队列中等待的一个线程，有以下两种情形：
                            //    a)若延迟队列中还有元素，此时需要唤醒一个仍在条件队列中等待的线程节点（否则可能会造成这个线程永远不会被唤醒），然后这个线程醒过来继续自旋，然后获取到头节点，拿到剩余延迟时间，执行到if (leader == null)的分支，再次阻塞剩余延迟时间，同时成为leader线程；
                            //    b)若延迟队列中已经没有元素了，此时没必要唤醒仍在条件队列中等待的一个线程，这种情形下，等下次有一个元素入队（put或offer）时，自然会唤醒这个线程，然后执行前面a)步骤的再次自旋逻辑
                            // 2）新入队元素占据到了延迟队列队首，阻塞的take线程延迟时间还未到，但被唤醒，此时leader在offer方法已经置为null了，所以不用再将leader置为null，因此不满足if (leader == thisThread)条件，
                            //    此时再次自旋，自旋逻辑跟1）步骤一样
                            if (leader == thisThread)
                                leader = null;
                        }
                    }
                }
            }
        } finally {
            // 这里只要leader为null说明条件队列中的第一个线程节点出队了，而延迟队列又还有元素的话，此时需要继续唤醒条件队列的另一个正在等待的线程
            if (leader == null && q.peek() != null)
                available.signal();
            lock.unlock();
        }
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element with an expired delay is available on this queue,
     * or the specified wait time expires.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element with
     *         an expired delay becomes available
     * @throws InterruptedException {@inheritDoc}
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            for (;;) {
                E first = q.peek();
                if (first == null) {
                    if (nanos <= 0)
                        return null;
                    else
                        nanos = available.awaitNanos(nanos);
                } else {
                    long delay = first.getDelay(NANOSECONDS);
                    if (delay <= 0)
                        return q.poll();
                    if (nanos <= 0)
                        return null;
                    first = null; // don't retain ref while waiting
                    if (nanos < delay || leader != null)
                        nanos = available.awaitNanos(nanos);
                    else {
                        Thread thisThread = Thread.currentThread();
                        leader = thisThread;
                        try {
                            long timeLeft = available.awaitNanos(delay);
                            nanos -= delay - timeLeft;
                        } finally {
                            if (leader == thisThread)
                                leader = null;
                        }
                    }
                }
            }
        } finally {
            if (leader == null && q.peek() != null)
                available.signal();
            lock.unlock();
        }
    }

    /**
     * Retrieves, but does not remove, the head of this queue, or
     * returns {@code null} if this queue is empty.  Unlike
     * {@code poll}, if no expired elements are available in the queue,
     * this method returns the element that will expire next,
     * if one exists.
     *
     * @return the head of this queue, or {@code null} if this
     *         queue is empty
     */
    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.peek();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns first element only if it is expired.
     * Used only by drainTo.  Call only when holding lock.
     */
    private E peekExpired() {
        // assert lock.isHeldByCurrentThread();
        E first = q.peek();
        return (first == null || first.getDelay(NANOSECONDS) > 0) ?
            null : first;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = 0;
            for (E e; (e = peekExpired()) != null;) {
                c.add(e);       // In this order, in case add() throws.
                q.poll();
                ++n;
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = 0;
            for (E e; n < maxElements && (e = peekExpired()) != null;) {
                c.add(e);       // In this order, in case add() throws.
                q.poll();
                ++n;
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically removes all of the elements from this delay queue.
     * The queue will be empty after this call returns.
     * Elements with an unexpired delay are not waited for; they are
     * simply discarded from the queue.
     */
    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            q.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because
     * a {@code DelayQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE}
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * Returns an array containing all of the elements in this queue.
     * The returned array elements are in no particular order.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.toArray();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue; the
     * runtime type of the returned array is that of the specified array.
     * The returned array elements are in no particular order.
     * If the queue fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of the
     * specified array and the size of this queue.
     *
     * <p>If this queue fits in the specified array with room to spare
     * (i.e., the array has more elements than this queue), the element in
     * the array immediately following the end of the queue is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>The following code can be used to dump a delay queue into a newly
     * allocated array of {@code Delayed}:
     *
     * <pre> {@code Delayed[] a = q.toArray(new Delayed[0]);}</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this queue
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.toArray(a);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a single instance of the specified element from this
     * queue, if it is present, whether or not it has expired.
     */
    public boolean remove(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return q.remove(o);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Identity-based version for use in Itr.remove
     */
    void removeEQ(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            for (Iterator<E> it = q.iterator(); it.hasNext(); ) {
                if (o == it.next()) {
                    it.remove();
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over all the elements (both expired and
     * unexpired) in this queue. The iterator does not return the
     * elements in any particular order.
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue
     */
    public Iterator<E> iterator() {
        return new Itr(toArray());
    }

    /**
     * Snapshot iterator that works off copy of underlying q array.
     */
    private class Itr implements Iterator<E> {
        final Object[] array; // Array of all elements
        int cursor;           // index of next element to return
        int lastRet;          // index of last element, or -1 if no such

        Itr(Object[] array) {
            lastRet = -1;
            this.array = array;
        }

        public boolean hasNext() {
            return cursor < array.length;
        }

        @SuppressWarnings("unchecked")
        public E next() {
            if (cursor >= array.length)
                throw new NoSuchElementException();
            lastRet = cursor;
            return (E)array[cursor++];
        }

        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            removeEQ(array[lastRet]);
            lastRet = -1;
        }
    }

}
