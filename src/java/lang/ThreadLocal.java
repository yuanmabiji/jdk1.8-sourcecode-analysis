/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.lang;
import java.lang.ref.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * TODO 【QUESTION71】不使用线程池的情况下，当线程退出后但ThreadLocal对象仍然存活（比如作为某个存活类的静态变量），此时线程的threadLocals引用已经断开，
 *                    但是Entry的key（ThreadLocal实例）虽为弱引用但还有强引用连着，所以弱引用未断开，此时整个threadLocalMap对象会被回收吗？
 *                    自己根据多种情况多debug和学会用jvm工具来观察对象有没被回收！！！
 * ThreadLocal实质采用了线性探测法，因为是用ThreadLocal实例本身作为Key,因此若能调用set
 * 方法说明key肯定不为null，这也从侧面说明了ThreadLocal采用的线性探测法的key是不能为null的。
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * @author  Josh Bloch and Doug Lea
 * @since   1.2
 */
public class ThreadLocal<T> {
    /**
     * ThreadLocals rely on per-thread linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     */
    private static AtomicInteger nextHashCode =
        new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.
     */
    private static int nextHashCode() {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * @return the initial value for this thread-local
     */
    // 【QUESTION77】请问子类覆盖的initialValue是线程安全的吗？
    // 【ANSWER77】  线程不安全，因为调用initialValue方法是get返回null时，再由调用线程去调用initialValue方法初始化值的，
    //              若此时调用线程有n个且并发调用initialValue方法，此时若initialValue方法里操作的是成员变量或静态变量，此时
    //              绝对存在线程安全问题的。那set/remove等方法也是由其他调用线程发起调用,为何并发调用set/remove方法却是线程安全的呢？
    //              因为调用set/remove方法时，首先获得本线程的threadLocalMap对象，操作的是线程的本地副本，每个线程操作的副本都是线程
    //              本地的，因此存在资源隔离，不像initialValue的成员变量，是没有资源隔离的情况，故initialValue里的成员变量线程不安全（有必要的话进行同步），
    //              但操作set/remove方法是线程安全的。
    protected T initialValue() {
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal() {
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     */
    public T get() {
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            ThreadLocalMap.Entry e = map.getEntry(this);
            if (e != null) {
                @SuppressWarnings("unchecked")
                T result = (T)e.value;
                return result;
            }
        }
        return setInitialValue();
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * @return the initial value
     */
    private T setInitialValue() {
        T value = initialValue();
        Thread t = Thread.currentThread();
        ThreadLocalMap map = getMap(t);
        if (map != null)
            map.set(this, value);
        else
            createMap(t, value);
        return value;
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     */
    public void set(T value) {
        Thread t = Thread.currentThread();
        // 拿到当前线程的threadLocals（每个线程有一个ThreadLocalMap对象）
        ThreadLocalMap map = getMap(t);
        if (map != null)
            map.set(this, value);
        else
            createMap(t, value);
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
     public void remove() {
         ThreadLocalMap m = getMap(Thread.currentThread());
         if (m != null)
             m.remove(this);
     }

    /**
     * Get the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param  t the current thread
     * @return the map
     */
    ThreadLocalMap getMap(Thread t) {
        return t.threadLocals;
    }

    /**
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the map
     */
    void createMap(Thread t, T firstValue) {
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     *
     * @param  parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap is a customized hash map suitable only for
     * maintaining thread local values. No operations are exported
     * outside of the ThreadLocal class. The class is package private to
     * allow declaration of fields in class Thread.  To help deal with
     * very large and long-lived usages, the hash table entries use
     * WeakReferences for keys. However, since reference queues are not
     * used, stale entries are guaranteed to be removed only when
     * the table starts running out of space.
     * TODO 【QUESTION69】最后一句话怎么理解？难道跟自己的demo问题有关？
     */
    static class ThreadLocalMap {

        /**
         * The entries in this hash map extend WeakReference, using
         * its main ref field as the key (which is always a
         * ThreadLocal object).  Note that null keys (i.e. entry.get()
         * == null) mean that the key is no longer referenced, so the
         * entry can be expunged from table.  Such entries are referred to
         * as "stale entries" in the code that follows.
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /** The value associated with this ThreadLocal. */
            Object value;

            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        /**
         * The initial capacity -- MUST be a power of two.
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         */
        private Entry[] table;

        /**
         * The number of entries in the table.
         */
        private int size = 0;

        /**
         * The next size value at which to resize.
         */
        private int threshold; // Default to 0

        /**
         * Set the resize threshold to maintain at worst a 2/3 load factor.
         */
        private void setThreshold(int len) {
            // 可见，loadFactor为2/3
            threshold = len * 2 / 3;
        }

        /**
         * Increment i modulo len.
         */
        private static int nextIndex(int i, int len) {
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * Decrement i modulo len.
         */
        private static int prevIndex(int i, int len) {
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * Construct a new map initially containing (firstKey, firstValue).
         * ThreadLocalMaps are constructed lazily, so we only create
         * one when we have at least one entry to put in it.
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            // 初始化哈希表
            table = new Entry[INITIAL_CAPACITY];
            // 根据哈希值定位到桶的位置
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            // 存储第一个key-value，key为ThreadLocal实例，为弱引用
            table[i] = new Entry(firstKey, firstValue);
            size = 1;
            // 设置threshold，当达到这个值则进行扩容
            setThreshold(INITIAL_CAPACITY);
        }

        /**
         * Construct a new map including all Inheritable ThreadLocals
         * from given parent map. Called only by createInheritedMap.
         *
         * @param parentMap the map associated with parent thread.
         */
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            table = new Entry[len];

            for (int j = 0; j < len; j++) {
                Entry e = parentTable[j];
                if (e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    if (key != null) {
                        Object value = key.childValue(e.value);
                        Entry c = new Entry(key, value);
                        int h = key.threadLocalHashCode & (len - 1);
                        while (table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * Get the entry associated with key.  This method
         * itself handles only the fast path: a direct hit of existing
         * key. It otherwise relays to getEntryAfterMiss.  This is
         * designed to maximize performance for direct hits, in part
         * by making this method readily inlinable.
         *
         * @param  key the thread local object
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntry(ThreadLocal<?> key) {
            int i = key.threadLocalHashCode & (table.length - 1);
            Entry e = table[i];
            if (e != null && e.get() == key)
                return e;
            else
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * Version of getEntry method for use when key is not found in
         * its direct hash slot.
         *
         * @param  key the thread local object
         * @param  i the table index for key's hash code
         * @param  e the entry at table[i]
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            Entry[] tab = table;
            int len = tab.length;

            while (e != null) {
                ThreadLocal<?> k = e.get();
                if (k == key)
                    return e;
                if (k == null)
                    expungeStaleEntry(i);
                else
                    i = nextIndex(i, len);
                e = tab[i];
            }
            return null;
        }

        /**
         * Set the value associated with key.
         *
         * @param key the thread local object
         * @param value the value to be set
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            Entry[] tab = table;
            int len = tab.length;
            // 定位哈系桶的位置
            int i = key.threadLocalHashCode & (len-1);

            // 【注意】若e != null说明hash冲突了，明白这一点很重要
            // 那么什么情况下会出现hash冲突呢？大部分情况下当ThreadLocal被作为静态成员变量时，此时必然会导致hash冲突
            for (Entry e = tab[i]; e != null;  e = tab[i = nextIndex(i, len)]) {
                // 因为Entry间接继承了Reference，故可以通过Entry.get()来拿到弱引用key即ThreadLocal实例
                ThreadLocal<?> k = e.get();
                /*
                 *public class ThreadLocalDemo1 {
                    private static ThreadLocal<String> stringThreadLocal = new ThreadLocal<>();
                    public static void main(String[] args) {
                        for (int i = 0; i < 30; i++) {
                            stringThreadLocal.set("yuanmabiji");
                            System.gc();
                        }
                        stringThreadLocal.set("jinyue");
                    }
                  }
                  * 【注意】以上demo逻辑的代码会进入以下if (k == key)判断逻辑，因为stringThreadLocal被强引用，即使GC的话
                  * ThreadLocal对象也不会被回收。
                 */
                // 1）若hash冲突，此时若k == key，则说明弱引用k不等于null即还未被回收，此时直接替换旧值即可
                if (k == key) {
                    e.value = value;
                    return;
                }
                // 2）若hash冲突，若k == null，说明若引用k已经被回收（因为没有强引用指向弱引用的对象即ThreadLocal实例了），但此时entry还不为null，
                // 此时就要调用replaceStaleEntry(key, value, i)方法替换掉哈希槽i的entry,并做一些清理过期entry的动作
                // 【QUESTION70】什么情况下会出现key为null，entry不为null的情况呢？
                // 【ANSWER70】  当出现过期的entry时就会出现这种情况，当threadLocal实例作为Key时且threadLocal实例没有任何强引用时，此时发生一次GC就将Entry的key对threadLocal实例的弱引用
                if (k == null) {
                    replaceStaleEntry(key, value, i);
                    return;
                }
            }
            // 3）执行到这里，有两种情况：
            //    3.1）没有hash冲突，此时就新建一个Entry，放入哈希槽i位置即可；
            //    3.2）出现哈希冲突，前面通过了一轮for循环线性探测到了下一个为null的哈希槽位置，此时插入这个哈希槽
            // 然后调用cleanSomeSlots方法清理一部分key为null的Entry，【注意】这里是清理一部分，而不是全部Key为null的Entry哈
            tab[i] = new Entry(key, value);
            int sz = ++size;
            // 若没有清理到一个过期的entry且size大于等于threshold，此时进行扩容且对于已有的entry重新进行hash定位到新表
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                rehash();
        }

        /**
         * Remove the entry for key.
         */
        private void remove(ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);
            for (Entry e = tab[i]; e != null; e = tab[i = nextIndex(i, len)]) {
                if (e.get() == key) {
                    // 将Key的弱引用断掉
                    e.clear();
                    // 清理哈希表的entry及entry.value
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

        /**
         * Replace a stale entry encountered during a set operation
         * with an entry for the specified key.  The value passed in
         * the value parameter is stored in the entry, whether or not
         * an entry already exists for the specified key.
         *
         * As a side effect, this method expunges all stale entries in the
         * "run" containing the stale entry.  (A run is a sequence of entries
         * between two null slots.)
         *
         * @param  key the key
         * @param  value the value to be associated with key
         * @param  staleSlot index of the first stale entry encountered while
         *         searching for key.
         */
        private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                       int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).
            int slotToExpunge = staleSlot;
            // 【向前遍历】从staleSlot位置往前找一直找到e为null的那个哈希槽的下一个哈希槽（这个哈希槽的entry不为null但key为null），并把这个哈希槽位置赋值给slotToExpunge
            // 【QUESTION72】假如整个哈希表都是entry不为null，但entry。key为null，此时就出现了死循环？
            // 【ANSWER72】不会出现死循环，原因就是当哈希表的容量大小达到一定数量时，此时会进行扩容，那么总有空槽即entry为null的槽
            for (int i = prevIndex(staleSlot, len); (e = tab[i]) != null; i = prevIndex(i, len))
                if (e.get() == null)
                    slotToExpunge = i;

            // Find either the key or trailing null slot of run, whichever
            // occurs first
            // 【向后遍历】从staleSlot位置往后找到下一个哈希槽不为null的entry
            for (int i = nextIndex(staleSlot, len); (e = tab[i]) != null; i = nextIndex(i, len)) {
                // 拿到Key
                ThreadLocal<?> k = e.get();

                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.
                // 如果往后找到的这个槽的k不为null，说明这个ThreadLocal实例key还未被回收，且k == key的话，说明这次要存的key与前一个槽过期的entry的k存在哈希冲突，说明之前存这个key时线性探测到下一个位置（即当前位置）去了，
                // 这种情况下，找到了真爱，直接将这个要插入的key对应的entry调整到原来的正确哈希位置（因为占着正确哈希位置的entry过期了，占着茅坑不拉屎，让它滚到当前位置来），即位置交换哈

                if (k == key) {

                    e.value = value;

                    tab[i] = tab[staleSlot];
                    // 将staleSlot下一个哈希槽位置的entry放到staleSlot哈希槽位置
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists
                    // 如果之前从staleSlot这个位置开始向前直到遇到某个null的哈希槽为止都找没找到过期的哈希槽，所以此时slotToExpunge还是原来赋值的staleSlot，
                    // 此时又因为从staleSlot位置向后找到了相同的key，对于这种情况又因为前面已经交换了位置，所以这里将i赋给slotToExpunge，即之后从这个位置开始清理即可
                    if (slotToExpunge == staleSlot)
                        slotToExpunge = i;
                    // 再从返回的null位置的下一个位置开始清理一些过期的entry，这里是从slotToExpunge这个哈希槽位置开始清理连续的过期的entry哈
                    // 因为expungeStaleEntry(slotToExpunge)做的是清理一段连续的过期的entry，不可能清理掉那些不连续的过期的entry，此外，在调用
                    // expungeStaleEntry(slotToExpunge)方法清理过程中，GC又发生了，很可能某些entry的key弱引用又被断掉，因此又产生了一些新的过期的entry
                    // 因此再次调用cleanSomeSlots方法做一次时间复杂度为O（logn）的过期entry的清理，而不是全表清理哈（为了性能）
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                // 如果当前哈希槽存储的entry已经过期，且向前扫描时也没找到过期的entry，因为slotToExpunge == staleSlot，
                // 此时就将当前哈希槽的位置赋给slotToExpunge，因为之前staleSlot槽的entry虽然也已经过期，但是此时要替换为新的entry哈
                if (k == null && slotToExpunge == staleSlot)
                    slotToExpunge = i;
            }
            // 执行到这里，说明虽然新的Key哈希冲突了，但是通过线性探测没找到真爱即没找到这个key对应的entry（说明当前哈希表不存在这个key对应的entry）
            // 此时现将原来staleSlot哈希槽过期的entry的value置空，然后再新建一个entry放到这个staleSlot哈希槽位置
            // If key not found, put new entry in stale slot
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // If there are any other stale entries in run, expunge them
            // 如果slotToExpunge == staleSlot，说明向前遍历没找到过期的entry且向后遍历也没有过期的entry，此时原来哈希定位到的过期的entry又被直接替换了，因此此时不用清理
            // （暂时不管那些gc新产生的过期entry（可能会有，可能无，所以此时没必要扫描清理来浪费性能））
            // 执行到这里，说明要么先前要么先后遍历找到了过期的entry了
            if (slotToExpunge != staleSlot)
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        /**
         * TODO 【QUESTION73】为何WeakHashMap需要引入referenceQueue来清理过期的数据呢？而ThreadLocal却没有？跟WeakHashMap的拉链法有关？
         * Expunge a stale entry by rehashing any possibly colliding entries
         * lying between staleSlot and the next null slot.  This also expunges
         * any other stale entries encountered before the trailing null.  See
         * Knuth, Section 6.4
         *
         * @param staleSlot index of slot known to have null key
         * @return the index of the next null slot after staleSlot
         * (all between staleSlot and this slot will have been checked
         * for expunging).
         */
        private int expungeStaleEntry(int staleSlot) {
            Entry[] tab = table;
            int len = tab.length;
            // 首先清理staleSlot位置的entry
            // expunge entry at staleSlot
            tab[staleSlot].value = null;
            tab[staleSlot] = null;
            size--;

            // Rehash until we encounter null
            Entry e;
            int i;
            // 从staleSlot位置开始寻找下一个不为null哈希槽的entry
            for (i = nextIndex(staleSlot, len); (e = tab[i]) != null; i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                // 如果找到的下一个哈希槽的entry的key为null，说明已经被回收过期了，此时直接清理这个槽的entry即可
                if (k == null) {
                    e.value = null;
                    tab[i] = null;
                    size--;
                // 如果找到的下一个哈希槽的entry的key不为null，说明key对应的entry还未过期,此时若k对哈希表长度重新取模后定位的哈希槽位置与当前位置不一致，
                // 则说明之前的set操作发生了rehash操作，此时基于resize后的新表重新定位新的哈希槽，若哈希冲突，继续线性探测找到下一个空槽插入即可
                } else {
                    int h = k.threadLocalHashCode & (len - 1);
                    if (h != i) {
                        tab[i] = null;

                        // Unlike Knuth 6.4 Algorithm R, we must scan until
                        // null because multiple entries could have been stale.
                        while (tab[h] != null)
                            h = nextIndex(h, len);
                        tab[h] = e;
                    }
                }
            }
            // the index of the next null slot after staleSlot
            // i为staleSlot的下一个null位置，这个staleSlot为传入的staleSlot开始的最后一个staleSlot
            return i;
        }

        /**
         * Heuristically[试探性地] scan some cells looking for stale entries.
         * This is invoked when either a new element is added, or
         * another stale one has been expunged. It performs a
         * logarithmic[对数] number of scans, as a balance between no
         * scanning (fast but retains garbage) and a number of scans
         * proportional to number of elements, that would find all
         * garbage but would cause some insertions to take O(n) time.
         *
         * @param i a position known NOT to hold a stale entry. The
         * scan starts at the element after i.这个i位置要么不存储entry，要么存储的entry的key没过期 TODO 【QUESTION74】 若threadLocal作为成员变量一直存活，但现场已经销毁了，这个threadLocalMap会被回收吗？
         * @param n scan control: {@code log2(n)} cells are scanned,
         * unless a stale entry is found, in which case
         * {@code log2(table.length)-1} additional cells are scanned.
         * When called from insertions, this parameter is the number
         * of elements, but when from replaceStaleEntry, it is the
         * table length. (Note: all this could be changed to be either
         * more or less aggressive by weighting n instead of just
         * using straight log n. But this version is simple, fast, and
         * seems to work well.)
         *
         * @return true if any stale entries have been removed.
         */
        // 作为权衡的一个方法，清理一些过期的槽
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            Entry[] tab = table;
            int len = tab.length;
            do {
                i = nextIndex(i, len);
                Entry e = tab[i];
                /*
                public class ThreadLocalDemo5 {
                    public static void main(String[] args) throws Exception{
                        for (int i = 0; i < 30; i++) {
                            setThreadLocal();
                            System.gc();
                        }
                    }
                    public static void setThreadLocal() {
                        ThreadLocal<String> stringThreadLocal = new ThreadLocal<>();
                        stringThreadLocal.set("yuanmabiji");
                    }
                }
                【注意】这个demo逻辑的代码会当刚好扫到hash槽的Entry的key为null时，会进入以下if的逻辑
                 */
                if (e != null && e.get() == null) {
                    n = len;
                    removed = true;
                    i = expungeStaleEntry(i);
                }
            } while ( (n >>>= 1) != 0);
            return removed;
        }

        /**
         * Re-pack and/or re-size the table. First scan the entire
         * table removing stale entries. If this doesn't sufficiently
         * shrink the size of the table, double the table size.
         */
        private void rehash() {
            // 首先进行全表的清理过期的entry
            expungeStaleEntries();
            // 若清理后仍然size >= threshold - threshold / 4，那么进行resize且对旧表的数据进行重新hash定位到新表
            // Use lower threshold for doubling to avoid hysteresis
            if (size >= threshold - threshold / 4)
                resize();
        }

        /**
         * Double the capacity of the table.
         */
        private void resize() {
            Entry[] oldTab = table;
            int oldLen = oldTab.length;
            int newLen = oldLen * 2;
            Entry[] newTab = new Entry[newLen];
            int count = 0;

            for (int j = 0; j < oldLen; ++j) {
                Entry e = oldTab[j];
                if (e != null) {
                    ThreadLocal<?> k = e.get();
                    if (k == null) {
                        e.value = null; // Help the GC
                    } else {
                        int h = k.threadLocalHashCode & (newLen - 1);
                        // 线性探测
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        newTab[h] = e;
                        count++;
                    }
                }
            }

            setThreshold(newLen);
            size = count;
            table = newTab;
        }

        /**
         * 【QUESTION74】当threadLocal为null，但线程池的线程还未销毁的情况下，expungeStaleEntry相关方法不是会清理过期entry，此时为啥还会出现内存泄露呢？
         * 【ANSWER74】 首先得明白调用expungeXxx/cleanXxx等方法是什么时候触发的，因为清理过期数据的这些方法无非是当前线程调用ThreadLocal的set/get/remove
         * 等方法调用触发的，而没有另外的后台线程去做这些清理动作的。当前线程通过ThreadLocal强引用的方式（TODO 直接通过ThreadLocal实例的话待分析）调用ThreadLocal
         * 的set方法时，说明当前ThreadLocal实例仍然有强引用，因此Entry的key弱引用不会被断掉，此时该entry就不会成为过期的entry，因此当触发
         * expungeXxx/cleanXxx等方法清理过期entry时，不会清理本次set的值。如果没有remove的话，则出现了内存泄露！
         * Expunge all stale entries in the table.
         */
        private void expungeStaleEntries() {
            Entry[] tab = table;
            int len = tab.length;
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.get() == null)
                    expungeStaleEntry(j);
            }
        }
    }
}
