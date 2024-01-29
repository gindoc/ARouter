package com.alibaba.android.arouter.core;

import android.content.Context;
import android.util.LruCache;

import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.facade.service.AutowiredService;
import com.alibaba.android.arouter.facade.template.ISyringe;

import java.util.ArrayList;
import java.util.List;

import static com.alibaba.android.arouter.utils.Consts.SUFFIX_AUTOWIRED;

/**
 * param inject service impl.
 *
 * @author zhilong <a href="mailto:zhilong.lzl@alibaba-inc.com">Contact me.</a>
 * @version 1.0
 * @since 2017/2/28 下午6:08
 */
@Route(path = "/arouter/service/autowired")
public class AutowiredServiceImpl implements AutowiredService {

    /**
     * 以目标类的类名为key，辅助类{clazz.simpleName}$$ARouter$$Autowired为value进行lru缓存，避免频繁创建对象
     */
    private LruCache<String, ISyringe> classCache;

    /**
     * 黑名单
     * 如果目标类对应的辅助类{clazz.simpleName}$$ARouter$$Autowired通过反射实例化失败时，
     * 会将目标类的类名放入黑名单中，下次就不会再实例化对应的辅助类
     */
    private List<String> blackList;

    @Override
    public void init(Context context) {
        classCache = new LruCache<>(50);
        blackList = new ArrayList<>();
    }

    /**
     * 为当前类instance注入变量值
     * @param instance the instance who need autowired. 当前类
     */
    @Override
    public void autowire(Object instance) {
        doInject(instance, null);
    }

    /**
     * Recursive injection
     * 当前类instance注入变量值完成后，递归父类继续注入父类中的变量值
     *
     * @param instance who call me.     当前类
     * @param parent   parent of me.    父类
     */
    private void doInject(Object instance, Class<?> parent) {
        Class<?> clazz = null == parent ? instance.getClass() : parent;

        // 获取目标类clazz对应的辅助类{clazz.simpleName}$$ARouter$$Autowired
        ISyringe syringe = getSyringe(clazz);
        if (null != syringe) {
            // 关键：将当前类传给辅助类{clazz.simpleName}$$ARouter$$Autowired，利用辅助类为当前类instance注入变量值
            syringe.inject(instance);
        }

        // 获取目标类的父类
        Class<?> superClazz = clazz.getSuperclass();
        // has parent and its not the class of framework.
        if (null != superClazz && !superClazz.getName().startsWith("android")) {
            // 如果父类不是framework中的类，则递归注入变量值
            doInject(instance, superClazz);
        }
    }

    /**
     * 从classCache中获取目标类clazz对应的辅助类{clazz.simpleName}$$ARouter$$Autowired
     * @param clazz 目标类
     * @return      如果classCache中存在目标类clazz对应的辅助类 {clazz.simpleName}$$ARouter$$Autowired，则返回；否则返回null
     */
    private ISyringe getSyringe(Class<?> clazz) {
        String className = clazz.getName();

        try {
            if (!blackList.contains(className)) {
                // 如果目标类不在黑名单中，则根据类名从classCache中获取对应的辅助类{clazz.simpleName}$$ARouter$$Autowired
                ISyringe syringeHelper = classCache.get(className);
                if (null == syringeHelper) {  // No cache.
                    // 如果classCache不存在对应的辅助类，则反射实例化一个
                    syringeHelper = (ISyringe) Class.forName(clazz.getName() + SUFFIX_AUTOWIRED).getConstructor().newInstance();
                }
                // 再次将辅助类缓存到classCache中
                classCache.put(className, syringeHelper);
                // 返回辅助类
                return syringeHelper;
            }
        } catch (Exception e) {
            // 辅助类实例化失败，将类名加入黑名单，下次不再实例化
            blackList.add(className);    // This instance need not autowired.
        }

        // 如果辅助类实例化失败 或 目标类在黑名单中，则返回null
        return null;
    }
}
