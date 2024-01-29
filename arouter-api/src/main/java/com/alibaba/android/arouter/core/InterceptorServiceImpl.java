package com.alibaba.android.arouter.core;

import android.content.Context;

import com.alibaba.android.arouter.exception.HandlerException;
import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.facade.callback.InterceptorCallback;
import com.alibaba.android.arouter.facade.service.InterceptorService;
import com.alibaba.android.arouter.facade.template.IInterceptor;
import com.alibaba.android.arouter.thread.CancelableCountDownLatch;
import com.alibaba.android.arouter.utils.MapUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.alibaba.android.arouter.launcher.ARouter.logger;
import static com.alibaba.android.arouter.utils.Consts.TAG;

/**
 * All of interceptors
 *
 * @author zhilong <a href="mailto:zhilong.lzl@alibaba-inc.com">Contact me.</a>
 * @version 1.0
 * @since 2017/2/23 下午2:09
 */
@Route(path = "/arouter/service/interceptor")
public class InterceptorServiceImpl implements InterceptorService {

    /**
     * 拦截器是否已经实例化
     * 在init方法中会开启子线程去实例化Warehouse.interceptorsIndex中保存的拦截器类，
     * 并缓存到Warehouse.interceptors中，
     * 之后会将该标记置为true
     */
    private static boolean interceptorHasInit;

    /**
     * 因为init方法中会开启子线程去实例化Warehouse.interceptorsIndex中保存的拦截器类，
     * 所以doInterceptions方法中会通过锁机制，一直等待，直到Warehouse.interceptorsIndex中保存的拦截器类全
     * 都实例化完成，才会继续执行
     */
    private static final Object interceptorInitLock = new Object();

    @Override
    public void doInterceptions(final Postcard postcard, final InterceptorCallback callback) {
        if (MapUtils.isNotEmpty(Warehouse.interceptorsIndex)) {
            // 如果存在拦截器类

            // 检查拦截器类是否都已经实例化了，如果没有则阻塞等待
            checkInterceptorsInitStatus();

            if (!interceptorHasInit) {
                // 理论上在未初始化完拦截器前不会走到这里，估计是补刀逻辑
                callback.onInterrupt(new HandlerException("Interceptors initialization takes too much time."));
                return;
            }

            // 开启子线程执行拦截器逻辑，因为拦截器逻辑可能很耗时，避免阻塞主线程
            LogisticsCenter.executor.execute(new Runnable() {
                @Override
                public void run() {
                    // 初始化CancelableCountDownLatch，count等于拦截器数量
                    CancelableCountDownLatch interceptorCounter = new CancelableCountDownLatch(Warehouse.interceptors.size());
                    try {
                        // 从第0个拦截器开始，依次执行
                        _execute(0, interceptorCounter, postcard);
                        // 当所有拦截器逻辑都执行了onContinue，或拦截器执行超时，或某个拦截器执行了onInterrupt方法导致CountDownLatch取消了，才继续执行
                        interceptorCounter.await(postcard.getTimeout(), TimeUnit.SECONDS);
                        if (interceptorCounter.getCount() > 0) {    // Cancel the navigation this time, if it hasn't return anythings.
                            // 拦截器执行超时
                            callback.onInterrupt(new HandlerException("The interceptor processing timed out."));
                        } else if (null != postcard.getTag()) {    // Maybe some exception in the tag.
                            // 某个拦截器执行了onInterrupt方法导致CountDownLatch取消了, ps: CancelableCountDownLatch.cancel()会把count清0
                            callback.onInterrupt((Throwable) postcard.getTag());
                        } else {
                            // 所有拦截器都执行了onContinue方法
                            callback.onContinue(postcard);
                        }
                    } catch (Exception e) {
                        // 拦截器的process方法执行时报错了
                        callback.onInterrupt(e);
                    }
                }
            });
        } else {
            // 不存在拦截器
            callback.onContinue(postcard);
        }
    }

    /**
     * Excute interceptor
     * 执行拦截器
     *
     * @param index    current interceptor index    待执行的拦截器下标
     * @param counter  interceptor counter          拦截器计数器，每执行完成一个则-1
     * @param postcard routeMeta                    所有拦截都执行了onContinue后需要执行的路由信息
     */
    private static void _execute(final int index, final CancelableCountDownLatch counter, final Postcard postcard) {
        if (index < Warehouse.interceptors.size()) {
            // 根据下标从Warehouse.interceptors中获取拦截器
            IInterceptor iInterceptor = Warehouse.interceptors.get(index);
            // 执行对应拦截器的逻辑
            iInterceptor.process(postcard, new InterceptorCallback() {
                @Override
                public void onContinue(Postcard postcard) {
                    // 拦截器执行成功，countDownLatch计数-1，继续执行下一个拦截器
                    // Last interceptor excute over with no exception.
                    counter.countDown();
                    _execute(index + 1, counter, postcard);  // When counter is down, it will be execute continue ,but index bigger than interceptors size, then U know.
                }

                @Override
                public void onInterrupt(Throwable exception) {
                    // 拦截器执行失败
                    // Last interceptor execute over with fatal exception.

                    // 将异常信息保存到postcard的tag中，用于将错误信息通过该InterceptorServiceImpl传递给_ARouter，
                    // 然后_ARouter再通过NavigationCallback告知外界
                    postcard.setTag(null == exception ? new HandlerException("No message.") : exception);    // save the exception message for backup.
                    // 取消countDownLatch的await，继续执行后续逻辑（通过
                    // InterceptorServiceImpl的doInterceptions方法的
                    // InterceptorCallback.onInterrupt将错误信息告知外界
                    counter.cancel();
                    // Be attention, maybe the thread in callback has been changed,
                    // then the catch block(L207) will be invalid.
                    // The worst is the thread changed to main thread, then the app will be crash, if you throw this exception!
//                    if (!Looper.getMainLooper().equals(Looper.myLooper())) {    // You shouldn't throw the exception if the thread is main thread.
//                        throw new HandlerException(exception.getMessage());
//                    }
                }
            });
        }
    }

    /**
     * IProvider的init方法，在实例化时会被执行
     * @param context ctx
     */
    @Override
    public void init(final Context context) {
        // 开启子线程遍历Warehouse.interceptorsIndex中保存的拦截器类，通过反射实例化，并执行其init方法，
        // 同时保存到Warehouse.interceptors中
        // 拦截器的init方法可能很耗时，所以开启子线程，防止阻塞主线程
        LogisticsCenter.executor.execute(new Runnable() {
            @Override
            public void run() {
                if (MapUtils.isNotEmpty(Warehouse.interceptorsIndex)) {
                    // 如果存在拦截器，则遍历
                    for (Map.Entry<Integer, Class<? extends IInterceptor>> entry : Warehouse.interceptorsIndex.entrySet()) {
                        Class<? extends IInterceptor> interceptorClass = entry.getValue();
                        try {
                            // 反射无参构造函数实例化
                            IInterceptor iInterceptor = interceptorClass.getConstructor().newInstance();
                            // 调用拦截器的init方法
                            iInterceptor.init(context);
                            // 将拦截器对象缓存到Warehouse.interceptors中
                            Warehouse.interceptors.add(iInterceptor);
                        } catch (Exception ex) {
                            throw new HandlerException(TAG + "ARouter init interceptor error! name = [" + interceptorClass.getName() + "], reason = [" + ex.getMessage() + "]");
                        }
                    }

                    // 所有拦截器都实例化后将标记置为true
                    interceptorHasInit = true;

                    logger.info(TAG, "ARouter interceptors init over.");

                    synchronized (interceptorInitLock) {
                        // 通知doInterceptions方法可以继续执行了
                        interceptorInitLock.notifyAll();
                    }
                }
            }
        });
    }

    /**
     * 检查拦截器类是否都已经实例化了
     */
    private static void checkInterceptorsInitStatus() {
        synchronized (interceptorInitLock) {
            while (!interceptorHasInit) {   // 添加条件，防止虚假唤醒
                try {
                    // 每10s循环check一下
                    interceptorInitLock.wait(10 * 1000);
                } catch (InterruptedException e) {
                    throw new HandlerException(TAG + "Interceptor init cost too much time error! reason = [" + e.getMessage() + "]");
                }
            }
        }
    }
}
