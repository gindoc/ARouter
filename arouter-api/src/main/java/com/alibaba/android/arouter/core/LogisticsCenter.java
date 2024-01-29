package com.alibaba.android.arouter.core;

import android.content.Context;
import android.net.Uri;

import com.alibaba.android.arouter.exception.HandlerException;
import com.alibaba.android.arouter.exception.NoRouteFoundException;
import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.enums.TypeKind;
import com.alibaba.android.arouter.facade.model.RouteMeta;
import com.alibaba.android.arouter.facade.template.IInterceptorGroup;
import com.alibaba.android.arouter.facade.template.IProvider;
import com.alibaba.android.arouter.facade.template.IProviderGroup;
import com.alibaba.android.arouter.facade.template.IRouteGroup;
import com.alibaba.android.arouter.facade.template.IRouteRoot;
import com.alibaba.android.arouter.launcher.ARouter;
import com.alibaba.android.arouter.utils.ClassUtils;
import com.alibaba.android.arouter.utils.Consts;
import com.alibaba.android.arouter.utils.MapUtils;
import com.alibaba.android.arouter.utils.PackageUtils;
import com.alibaba.android.arouter.utils.TextUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import static com.alibaba.android.arouter.launcher.ARouter.logger;
import static com.alibaba.android.arouter.utils.Consts.AROUTER_SP_CACHE_KEY;
import static com.alibaba.android.arouter.utils.Consts.AROUTER_SP_KEY_MAP;
import static com.alibaba.android.arouter.utils.Consts.DOT;
import static com.alibaba.android.arouter.utils.Consts.ROUTE_ROOT_PAKCAGE;
import static com.alibaba.android.arouter.utils.Consts.SDK_NAME;
import static com.alibaba.android.arouter.utils.Consts.SEPARATOR;
import static com.alibaba.android.arouter.utils.Consts.SUFFIX_INTERCEPTORS;
import static com.alibaba.android.arouter.utils.Consts.SUFFIX_PROVIDERS;
import static com.alibaba.android.arouter.utils.Consts.SUFFIX_ROOT;
import static com.alibaba.android.arouter.utils.Consts.TAG;

/**
 * LogisticsCenter contains all of the map.
 * <p>
 * 1. Creates instance when it is first used.
 * 2. Handler Multi-Module relationship map(*)
 * 3. Complex logic to solve duplicate group definition
 *
 * @author Alex <a href="mailto:zhilong.liu@aliyun.com">Contact me.</a>
 * @version 1.0
 * @since 16/8/23 15:02
 */
public class LogisticsCenter {
    private static Context mContext;
    static ThreadPoolExecutor executor;
    private static boolean registerByPlugin;

    /**
     * arouter-auto-register plugin will generate code inside this method
     * call this method to register all Routers, Interceptors and Providers
     * 通过ARouter的插件，利用transform在编译时收集所有
     * `IRouteRoot`、`IProviderGroup`、`IInterceptorGroup` 的子类，
     * 然后在`LogisticsCenter.loadRouterMap()`中生成注册代码调用
     * `LogisticsCenter.register(String className)`方法，
     * 将transform收集到的类的类名传进去，从而执行对应类的`loadInto`方法。
     */
    private static void loadRouterMap() {
        registerByPlugin = false;
        // 自动生成注册代码，例如：
        // register("com.alibaba.android.arouter.routes.ARouter$$Root$$modulejava")
        // register("com.alibaba.android.arouter.routes.ARouter$$Interceptors$$modulejava")
        // register("com.alibaba.android.arouter.routes.ARouter$$Providers$$modulejava")
    }

    /**
     * register by class name
     * Sacrificing a bit of efficiency to solve
     * the problem that the main dex file size is too large
     * 通过反射实例化对应的子类并调用其loadInto方法
     */
    private static void register(String className) {
        logger.info(TAG, "className:" + className);
        if (!TextUtils.isEmpty(className)) {
            try {
                Class<?> clazz = Class.forName(className);
                Object obj = clazz.getConstructor().newInstance();
                if (obj instanceof IRouteRoot) {
                    registerRouteRoot((IRouteRoot) obj);
                } else if (obj instanceof IProviderGroup) {
                    registerProvider((IProviderGroup) obj);
                } else if (obj instanceof IInterceptorGroup) {
                    registerInterceptor((IInterceptorGroup) obj);
                } else {
                    logger.info(TAG, "register failed, class name: " + className
                            + " should implements one of IRouteRoot/IProviderGroup/IInterceptorGroup.");
                }
            } catch (Exception e) {
                logger.error(TAG,"register class error:" + className, e);
            }
        }
    }

    /**
     * 调用com.alibaba.android.arouter.core.routers包下实现了IRouteRoot的对象的loadInto方法，为Warehouse.groupsIndex赋值
     * method for arouter-auto-register plugin to register Routers
     * @param routeRoot IRouteRoot implementation class in the package: com.alibaba.android.arouter.core.routers
     */
    private static void registerRouteRoot(IRouteRoot routeRoot) {
        markRegisteredByPlugin();
        if (routeRoot != null) {
            routeRoot.loadInto(Warehouse.groupsIndex);
        }
    }

    /**
     * method for arouter-auto-register plugin to register Interceptors
     * @param interceptorGroup IInterceptorGroup implementation class in the package: com.alibaba.android.arouter.core.routers
     */
    private static void registerInterceptor(IInterceptorGroup interceptorGroup) {
        markRegisteredByPlugin();
        if (interceptorGroup != null) {
            interceptorGroup.loadInto(Warehouse.interceptorsIndex);
        }
    }

    /**
     * method for arouter-auto-register plugin to register Providers
     * @param providerGroup IProviderGroup implementation class in the package: com.alibaba.android.arouter.core.routers
     */
    private static void registerProvider(IProviderGroup providerGroup) {
        markRegisteredByPlugin();
        if (providerGroup != null) {
            providerGroup.loadInto(Warehouse.providersIndex);
        }
    }

    /**
     * mark already registered by arouter-auto-register plugin
     * 标识已经执行过arouter-auto-register 插件利用transform生成的注册代码了
     */
    private static void markRegisteredByPlugin() {
        if (!registerByPlugin) {
            registerByPlugin = true;
        }
    }

    /**
     * LogisticsCenter init, load all metas in memory. Demand initialization
     */
    public synchronized static void init(Context context, ThreadPoolExecutor tpe) throws HandlerException {
        mContext = context;
        executor = tpe;

        try {
            long startInit = System.currentTimeMillis();
            //load by plugin first
            // 首先执行transform生成的注册代码
            loadRouterMap();
            if (registerByPlugin) {
                // 已执行ARouter自动注册插件利用transform生成的注册代码
                logger.info(TAG, "Load router map by arouter-auto-register plugin.");
            } else {
                // 未执行ARouter自动注册插件利用transform生成的注册代码，利用反射实例化并调用对应类的loadInto方法
                Set<String> routerMap;

                // It will rebuild router map every times when debuggable.
                if (ARouter.debuggable() || PackageUtils.isNewVersion(context)) {
                    // 如果时debug模式 或 安装新版本app
                    logger.info(TAG, "Run with debug mode or new install, rebuild router map.");
                    // These class was generated by arouter-compiler.
                    // 收集所有dex中位于com.alibaba.android.arouter.routes包下的类(ps：这些类都是arouter-compiler利用apt注解处理器生成的)
                    routerMap = ClassUtils.getFileNameByPackageName(mContext, ROUTE_ROOT_PAKCAGE);
                    if (!routerMap.isEmpty()) {
                        // 将这些类保存到sp中
                        context.getSharedPreferences(AROUTER_SP_CACHE_KEY, Context.MODE_PRIVATE).edit().putStringSet(AROUTER_SP_KEY_MAP, routerMap).apply();
                    }

                    // 更新sp中的版本号
                    PackageUtils.updateVersion(context);    // Save new version name when router map update finishes.
                } else {
                    // 如果不是debug模式 且 不是安装新版本app
                    logger.info(TAG, "Load router map from cache.");
                    // 从sp中取出位于com.alibaba.android.arouter.routes包下的类
                    routerMap = new HashSet<>(context.getSharedPreferences(AROUTER_SP_CACHE_KEY, Context.MODE_PRIVATE).getStringSet(AROUTER_SP_KEY_MAP, new HashSet<String>()));
                }

                logger.info(TAG, "Find router map finished, map size = " + routerMap.size() + ", cost " + (System.currentTimeMillis() - startInit) + " ms.");
                startInit = System.currentTimeMillis();

                for (String className : routerMap) {
                    if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_ROOT)) {
                        // 如果位于com.alibaba.android.arouter.routes包下的类是以
                        // com.alibaba.android.arouter.routes.ARouter$$Root开头的，
                        // 则反射其无参构造函数实例化并调用其`loadInto`方法为Warehouse.groupsIndex赋值
                        // This one of root elements, load root.
                        ((IRouteRoot) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.groupsIndex);
                    } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_INTERCEPTORS)) {
                        // 如果位于com.alibaba.android.arouter.routes包下的类是以
                        // com.alibaba.android.arouter.routes.ARouter$$Interceptors开头的，
                        // 则反射其无参构造函数实例化并调用其`loadInto`方法为Warehouse.interceptorsIndex赋值
                        // Load interceptorMeta
                        ((IInterceptorGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.interceptorsIndex);
                    } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_PROVIDERS)) {
                        // 如果位于com.alibaba.android.arouter.routes包下的类是以
                        // com.alibaba.android.arouter.routes.ARouter$$Providers开头的，
                        // 则反射其无参构造函数实例化并调用其`loadInto`方法为Warehouse.providersIndex赋值
                        // Load providerIndex
                        ((IProviderGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.providersIndex);
                    }
                }
            }

            logger.info(TAG, "Load root element finished, cost " + (System.currentTimeMillis() - startInit) + " ms.");

            if (Warehouse.groupsIndex.size() == 0) {
                logger.error(TAG, "No mapping files were found, check your configuration please!");
            }

            if (ARouter.debuggable()) {
                logger.debug(TAG, String.format(Locale.getDefault(), "LogisticsCenter has already been loaded, GroupIndex[%d], InterceptorIndex[%d], ProviderIndex[%d]", Warehouse.groupsIndex.size(), Warehouse.interceptorsIndex.size(), Warehouse.providersIndex.size()));
            }
        } catch (Exception e) {
            throw new HandlerException(TAG + "ARouter init logistics center exception! [" + e.getMessage() + "]");
        }
    }

    /**
     * Build postcard by serviceName
     *
     * @param serviceName interfaceName
     * @return postcard
     */
    public static Postcard buildProvider(String serviceName) {
        // 根据service的全路径类名，从Warehouse.providersIndex中找到对应的RouteMeta
        RouteMeta meta = Warehouse.providersIndex.get(serviceName);

        if (null == meta) {
            return null;
        } else {
            // 构建Postcard并返回
            return new Postcard(meta.getPath(), meta.getGroup());
        }
    }

    /**
     * Completion the postcard by route metas
     * 根据postcard的path查找Warehouse.routes中对应的路由信息RouteMeta，并完善postcard
     * @param postcard 待完善的postcard，Incomplete postcard, should complete by this method.
     */
    public synchronized static void completion(Postcard postcard) {
        if (null == postcard) {
            throw new NoRouteFoundException(TAG + "No postcard!");
        }

        // 根据postcard的path，从Warehouse.routes中获取对应的RouteMeta
        RouteMeta routeMeta = Warehouse.routes.get(postcard.getPath());
        if (null == routeMeta) {
            // 如果Warehouse.routes中找不到，则可能不存在对应的RouteMeta 或 对应的RouteMeta还没加载到Warehouse.routes中
            // Maybe its does't exist, or didn't load.
            if (!Warehouse.groupsIndex.containsKey(postcard.getGroup())) {
                // 如果postcard对应的group，在Warehouse.groupsIndex中不存在，说明该postcard不存在对应的RouteMeta
                // Warehouse.groupsIndex在ARouter初始化时传给ARouter$$Root$${moduleName}.loadInto(Map<String, Class<? extends IRouteGroup>> routes)
                throw new NoRouteFoundException(TAG + "There is no route match the path [" + postcard.getPath() + "], in group [" + postcard.getGroup() + "]");
            } else {
                // Load route and cache it into memory, then delete from metas.
                try {
                    if (ARouter.debuggable()) {
                        logger.debug(TAG, String.format(Locale.getDefault(), "The group [%s] starts loading, trigger by [%s]", postcard.getGroup(), postcard.getPath()));
                    }

                    // 根据groupName动态添加路由信息到Warehouse.routes中
                    addRouteGroupDynamic(postcard.getGroup(), null);

                    if (ARouter.debuggable()) {
                        logger.debug(TAG, String.format(Locale.getDefault(), "The group [%s] has already been loaded, trigger by [%s]", postcard.getGroup(), postcard.getPath()));
                    }
                } catch (Exception e) {
                    throw new HandlerException(TAG + "Fatal exception when loading group meta. [" + e.getMessage() + "]");
                }

                // groupName对应的ARouter$$Group$${groupName}中的路由信息添加完后，重新执行completion方法完善postcard
                completion(postcard);   // Reload
            }
        } else {
            // 根据path对应的RouteMeta，完善postcard
            postcard.setDestination(routeMeta.getDestination());
            postcard.setType(routeMeta.getType());
            postcard.setPriority(routeMeta.getPriority());
            postcard.setExtra(routeMeta.getExtra());

            Uri rawUri = postcard.getUri();
            if (null != rawUri) {   // Try to set params into bundle.
                // 如果uri不为null，则尝试将uri中的参数放入postcard的bundle中

                // uri中的参数的key-value map
                Map<String, String> resultMap = TextUtils.splitQueryParameters(rawUri);
                // 以@Autowired的name属性的值为key，成员变量的类型对应的枚举的ordinal为value
                Map<String, Integer> paramsType = routeMeta.getParamsType();

                if (MapUtils.isNotEmpty(paramsType)) {
                    // Set value by its type, just for params which annotation by @Param
                    for (Map.Entry<String, Integer> params : paramsType.entrySet()) {
                        // 根据routeMeta中对应参数的数据类型 和 uri中对应参数的value，给postcard填充mBundle
                        setValue(postcard,
                                params.getValue(),
                                params.getKey(),
                                resultMap.get(params.getKey()));
                    }

                    // Save params name which need auto inject.
                    // 将参数名数组化并存入postcard的mBundle中，后续为Activity自动注入参数时会用到，忽略
                    postcard.getExtras().putStringArray(ARouter.AUTO_INJECT, paramsType.keySet().toArray(new String[]{}));
                }

                // Save raw uri
                // 将uri保存到postcard的mBundle中
                postcard.withString(ARouter.RAW_URI, rawUri.toString());
            }

            switch (routeMeta.getType()) {
                case PROVIDER:  // if the route is provider, should find its instance
                    // 如果routeMeta的类型时PROVIDER，有两种情况：
                    // 1. IProvider实现类直接implement IProvider
                    // 2. IProvider实现类 implement 的接口 extends IProvider

                    // Its provider, so it must implement IProvider
                    // 从routeMeta的destination获得IProvider具体实现类的Class对象
                    Class<? extends IProvider> providerMeta = (Class<? extends IProvider>) routeMeta.getDestination();
                    // 从Warehouse.providers获取IProvider具体实现类的实例对象
                    IProvider instance = Warehouse.providers.get(providerMeta);
                    if (null == instance) { // There's no instance of this provider
                        // IProvider具体实现类尚未初始化过
                        IProvider provider;
                        try {
                            // 反射IProvider具体实现类的构造函数初始化
                            provider = providerMeta.getConstructor().newInstance();
                            // 调用init方法
                            provider.init(mContext);
                            // 将IProvider具体实现类的实例保存到Warehouse.providers中
                            Warehouse.providers.put(providerMeta, provider);
                            instance = provider;
                        } catch (Exception e) {
                            logger.error(TAG, "Init provider failed!", e);
                            throw new HandlerException("Init provider failed!");
                        }
                    }
                    // 为postcard设置provider
                    postcard.setProvider(instance);
                    // PROVIDER类型的postcard默认设置绿色通道
                    postcard.greenChannel();    // Provider should skip all of interceptors
                    break;
                case FRAGMENT:
                    // FRAGMENT类型的postcard默认设置绿色通道
                    postcard.greenChannel();    // Fragment needn't interceptors
                default:
                    break;
            }
        }
    }

    /**
     * Set value by known type
     *
     * @param postcard postcard
     * @param typeDef  type
     * @param key      key
     * @param value    value
     */
    private static void setValue(Postcard postcard, Integer typeDef, String key, String value) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
            return;
        }

        try {
            if (null != typeDef) {
                if (typeDef == TypeKind.BOOLEAN.ordinal()) {
                    postcard.withBoolean(key, Boolean.parseBoolean(value));
                } else if (typeDef == TypeKind.BYTE.ordinal()) {
                    postcard.withByte(key, Byte.parseByte(value));
                } else if (typeDef == TypeKind.SHORT.ordinal()) {
                    postcard.withShort(key, Short.parseShort(value));
                } else if (typeDef == TypeKind.INT.ordinal()) {
                    postcard.withInt(key, Integer.parseInt(value));
                } else if (typeDef == TypeKind.LONG.ordinal()) {
                    postcard.withLong(key, Long.parseLong(value));
                } else if (typeDef == TypeKind.FLOAT.ordinal()) {
                    postcard.withFloat(key, Float.parseFloat(value));
                } else if (typeDef == TypeKind.DOUBLE.ordinal()) {
                    postcard.withDouble(key, Double.parseDouble(value));
                } else if (typeDef == TypeKind.STRING.ordinal()) {
                    postcard.withString(key, value);
                } else if (typeDef == TypeKind.PARCELABLE.ordinal()) {
                    // TODO : How to description parcelable value with string?
                } else if (typeDef == TypeKind.OBJECT.ordinal()) {
                    postcard.withString(key, value);
                } else {    // Compatible compiler sdk 1.0.3, in that version, the string type = 18
                    postcard.withString(key, value);
                }
            } else {
                postcard.withString(key, value);
            }
        } catch (Throwable ex) {
            logger.warning(Consts.TAG, "LogisticsCenter setValue failed! " + ex.getMessage());
        }
    }

    /**
     * Suspend business, clear cache.
     */
    public static void suspend() {
        Warehouse.clear();
    }

    public synchronized static void addRouteGroupDynamic(String groupName, IRouteGroup group) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        if (Warehouse.groupsIndex.containsKey(groupName)) {
            // 如果Warehouse.groupsIndex存在groupName，则说明对应的ARouter$$Group$${groupName}中的路由信息没加载到Warehouse.routes中
            // If this group is included, but it has not been loaded
            // load this group first, because dynamic route has high priority.
            // 通过反射实例化ARouter$$Group$${groupName}，并将Warehouse.routes传给loadInto方法加载路由信息
            Warehouse.groupsIndex.get(groupName).getConstructor().newInstance().loadInto(Warehouse.routes);
            // ARouter$$Group$${groupName}中的路由信息加载完后，将它从Warehouse.groupsIndex中移除，避免动态添加路由时传的相同的groupName导致重复加载
            Warehouse.groupsIndex.remove(groupName);
        }

        // cover old group.
        if (null != group) {
            // 如果group不为空，则将Warehouse.routes传给它的loadInto方法加载路由信息，外部通过ARouter动态加载路由信息时会传group参数
            group.loadInto(Warehouse.routes);
        }
    }
}
