/*
 * Copyright (c) 1998, 2012, Oracle and/or its affiliates. All rights reserved.
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

/**
 * 【QUESTION75】 为什么父线程set的值子线程能拿到呢？而子线程set的值父线程却不能拿到呢？
 * 【ANSWER75】   1，在父线程新建InheritableThreadLocal实例并set值后，此时该值保存在父线程的inheritableThreadLocals引用的ThreadLocalMap里，
 *                  key是InheritableThreadLocal实例，value是值。当父线程创建子线程时，此时会默认调用子线程无参的构造方法，而子线程无参的构造方法
 *                  最终会调用init方法，正是在这个init方法拿到通过Thread.currentThread拿到父线程（因为子线程是在父线程中创建，因此当前线程是父线程），
 *                  的inheritableThreadLocals引用的ThreadLocalMap，在子线程的init方法中将父线程的ThreadLocalMap里的值拿出来装进子线程的ThreadLocalMap里，
 *                  key还是InheritableThreadLocal实例，value还是父线程set的值。因此子线程就能拿到父线程的值啦。
 * This class extends <tt>ThreadLocal</tt> to provide inheritance of values
 * from parent thread to child thread: when a child thread is created, the
 * child receives initial values for all inheritable thread-local variables
 * for which the parent has values.  Normally the child's values will be
 * identical to the parent's; however, the child's value can be made an
 * arbitrary function of the parent's by overriding the <tt>childValue</tt>
 * method in this class.
 *
 * <p>Inheritable thread-local variables are used in preference to
 * ordinary thread-local variables when the per-thread-attribute being
 * maintained in the variable (e.g., User ID, Transaction ID) must be
 * automatically transmitted to any child threads that are created.
 *
 * @author  Josh Bloch and Doug Lea
 * @see     ThreadLocal
 * @since   1.2
 */

public class InheritableThreadLocal<T> extends ThreadLocal<T> {
    /**
     * Computes the child's initial value for this inheritable thread-local
     * variable as a function of the parent's value at the time the child
     * thread is created.  This method is called from within the parent
     * thread before the child is started.
     * <p>
     * This method merely returns its input argument, and should be overridden
     * if a different behavior is desired.
     *
     * @param parentValue the parent thread's value
     * @return the child thread's initial value
     */
    protected T childValue(T parentValue) {
        return parentValue;
    }

    /**
     * Get the map associated with a ThreadLocal.
     *
     * @param t the current thread
     */
    ThreadLocalMap getMap(Thread t) {
       return t.inheritableThreadLocals;
    }

    /**
     * Create the map associated with a ThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the table.
     */
    void createMap(Thread t, T firstValue) {
        t.inheritableThreadLocals = new ThreadLocalMap(this, firstValue);
    }
}
