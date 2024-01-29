package com.alibaba.android.arouter.core;

import com.alibaba.android.arouter.base.UniqueKeyTreeMap;
import com.alibaba.android.arouter.facade.model.RouteMeta;
import com.alibaba.android.arouter.facade.template.IInterceptor;
import com.alibaba.android.arouter.facade.template.IProvider;
import com.alibaba.android.arouter.facade.template.IRouteGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage of route meta and other data.
 *
 * @author zhilong <a href="mailto:zhilong.lzl@alibaba-inc.com">Contact me.</a>
 * @version 1.0
 * @since 2017/2/23 下午1:39
 */
class Warehouse {
    /**
     * Cache route and metas
     * 以groupName为key，IRouteGroup为value
     * 初始化时传给ARouter$$Root$${moduleName}.loadInto(Map<String, Class<? extends IRouteGroup>> routes)
     */
    static Map<String, Class<? extends IRouteGroup>> groupsIndex = new HashMap<>();

    /**
     * 以path为key，Activity/Fragment/IProvider相关的RouteMeta为value
     * 根据path找不到对应的RouteMeta时 或者 动态添加路由时，会传给ARouter$$Group$${groupName}.loadInto(Map<String, RouteMeta> atlas)
     */
    static Map<String, RouteMeta> routes = new HashMap<>();

    /**
     * Cache provider
     * 第一次实例化IProvider实现类后，缓存起来，后续就不需要重复实例化
     * 以IProvider实现类的Class对象为key，IProvider实现类的实例为value
     */
    static Map<Class, IProvider> providers = new HashMap<>();

    /**
     * 以 @Route修饰的IProvider实现类 / 或其接口 为key，IProvider实现类的RouteMeta为value
     * 初始化时传给ARouter$$Providers$${moduleName}.loadInto(Map<String, RouteMeta> providers)
     */
    static Map<String, RouteMeta> providersIndex = new HashMap<>();

    /**
     * Cache interceptor
     * 以priority为key，拦截器的实现类的class对象为value
     * 初始化时传给ARouter$$Interceptors$${moduleName}.loadInto(Map<Integer, Class<? extends IInterceptor>> interceptors)
     */
    static Map<Integer, Class<? extends IInterceptor>> interceptorsIndex = new UniqueKeyTreeMap<>("More than one interceptors use same priority [%s]");

    /**
     * InterceptorServiceImpl初始化时会实例化interceptorsIndex中保存的拦截器类，然后混存到interceptors中，
     * 然后在_ARouter.navigation(...)时遍历interceptors中缓存的拦截器，执行各拦截器的process方法
     */
    static List<IInterceptor> interceptors = new ArrayList<>();

    static void clear() {
        routes.clear();
        groupsIndex.clear();
        providers.clear();
        providersIndex.clear();
        interceptors.clear();
        interceptorsIndex.clear();
    }
}
