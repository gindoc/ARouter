package com.alibaba.android.arouter.compiler.processor;

import com.alibaba.android.arouter.compiler.entity.RouteDoc;
import com.alibaba.android.arouter.compiler.utils.Consts;
import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.alibaba.android.arouter.facade.annotation.Route;
import com.alibaba.android.arouter.facade.enums.RouteType;
import com.alibaba.android.arouter.facade.enums.TypeKind;
import com.alibaba.android.arouter.facade.model.RouteMeta;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;

import static com.alibaba.android.arouter.compiler.utils.Consts.ACTIVITY;
import static com.alibaba.android.arouter.compiler.utils.Consts.ANNOTATION_TYPE_AUTOWIRED;
import static com.alibaba.android.arouter.compiler.utils.Consts.ANNOTATION_TYPE_ROUTE;
import static com.alibaba.android.arouter.compiler.utils.Consts.FRAGMENT;
import static com.alibaba.android.arouter.compiler.utils.Consts.IPROVIDER_GROUP;
import static com.alibaba.android.arouter.compiler.utils.Consts.IROUTE_GROUP;
import static com.alibaba.android.arouter.compiler.utils.Consts.ITROUTE_ROOT;
import static com.alibaba.android.arouter.compiler.utils.Consts.METHOD_LOAD_INTO;
import static com.alibaba.android.arouter.compiler.utils.Consts.NAME_OF_GROUP;
import static com.alibaba.android.arouter.compiler.utils.Consts.NAME_OF_PROVIDER;
import static com.alibaba.android.arouter.compiler.utils.Consts.NAME_OF_ROOT;
import static com.alibaba.android.arouter.compiler.utils.Consts.PACKAGE_OF_GENERATE_DOCS;
import static com.alibaba.android.arouter.compiler.utils.Consts.PACKAGE_OF_GENERATE_FILE;
import static com.alibaba.android.arouter.compiler.utils.Consts.SEPARATOR;
import static com.alibaba.android.arouter.compiler.utils.Consts.SERVICE;
import static com.alibaba.android.arouter.compiler.utils.Consts.WARNING_TIPS;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * A processor used for find route.
 *
 * @author Alex <a href="mailto:zhilong.liu@aliyun.com">Contact me.</a>
 * @version 1.0
 * @since 16/8/15 下午10:08
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({ANNOTATION_TYPE_ROUTE, ANNOTATION_TYPE_AUTOWIRED})
public class RouteProcessor extends BaseProcessor {

    // 以group为key，类的@Route注解信息的Set为value，是个TreeSet，以RouteMeta中的path进行排序
    private Map<String, Set<RouteMeta>> groupMap = new HashMap<>(); // ModuleName and routeMeta.

    // 以group为key，groupFileName为value，eg：["test", "ARouter$$Group$$test"]
    private Map<String, String> rootMap = new TreeMap<>();  // Map of root metas, used for generate class file in order.

    // Provider接口类型
    private TypeMirror iProvider = null;
    private Writer docWriter;       // Writer used for write doc

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        // 获取生成文档
        if (generateDoc) {
            try {
                docWriter = mFiler.createResource(
                        StandardLocation.SOURCE_OUTPUT,
                        PACKAGE_OF_GENERATE_DOCS,
                        "arouter-map-of-" + moduleName + ".json"
                ).openWriter();
            } catch (IOException e) {
                logger.error("Create doc writer failed, because " + e.getMessage());
            }
        }

        // Provider接口类型
        iProvider = elementUtils.getTypeElement(Consts.IPROVIDER).asType();

        logger.info(">>> RouteProcessor init. <<<");
    }

    /**
     * {@inheritDoc}
     *
     * @param annotations
     * @param roundEnv
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (CollectionUtils.isNotEmpty(annotations)) {
            // 获取被@Route修饰的类
            Set<? extends Element> routeElements = roundEnv.getElementsAnnotatedWith(Route.class);
            try {
                logger.info(">>> Found routes, start... <<<");
                // 解析元素
                this.parseRoutes(routeElements);

            } catch (Exception e) {
                logger.error(e);
            }
            return true;
        }

        return false;
    }

    private void parseRoutes(Set<? extends Element> routeElements) throws IOException {
        if (CollectionUtils.isNotEmpty(routeElements)) {
            // prepare the type an so on.

            logger.info(">>> Found routes, size is " + routeElements.size() + " <<<");

            rootMap.clear();

            // 获取Activity、Service、Fragment的类型
            TypeMirror type_Activity = elementUtils.getTypeElement(ACTIVITY).asType();
            TypeMirror type_Service = elementUtils.getTypeElement(SERVICE).asType();
            TypeMirror fragmentTm = elementUtils.getTypeElement(FRAGMENT).asType();
            TypeMirror fragmentTmV4 = elementUtils.getTypeElement(Consts.FRAGMENT_V4).asType();

            // 获取IRouteGroup、IProviderGroup的类型和RouteMeta、RouteType的类名
            // Interface of ARouter
            TypeElement type_IRouteGroup = elementUtils.getTypeElement(IROUTE_GROUP);
            TypeElement type_IProviderGroup = elementUtils.getTypeElement(IPROVIDER_GROUP);
            ClassName routeMetaCn = ClassName.get(RouteMeta.class);
            ClassName routeTypeCn = ClassName.get(RouteType.class);

            /*
               构建函数参数类型
               Build input type, format as :

               ```Map<String, Class<? extends IRouteGroup>>```
             */
            ParameterizedTypeName inputMapTypeOfRoot = ParameterizedTypeName.get(
                    ClassName.get(Map.class),
                    ClassName.get(String.class),
                    ParameterizedTypeName.get(
                            ClassName.get(Class.class),
                            WildcardTypeName.subtypeOf(ClassName.get(type_IRouteGroup))
                    )
            );

            /*
                构建函数参数类型
              ```Map<String, RouteMeta>```
             */
            ParameterizedTypeName inputMapTypeOfGroup = ParameterizedTypeName.get(
                    ClassName.get(Map.class),
                    ClassName.get(String.class),
                    ClassName.get(RouteMeta.class)
            );

            /*
                构建函数参数名
              Build input param name.
             */
            ParameterSpec rootParamSpec = ParameterSpec.builder(inputMapTypeOfRoot, "routes").build();
            ParameterSpec groupParamSpec = ParameterSpec.builder(inputMapTypeOfGroup, "atlas").build();
            ParameterSpec providerParamSpec = ParameterSpec.builder(inputMapTypeOfGroup, "providers").build();  // Ps. its param type same as groupParamSpec!

            /*
                构建loadInto函数
              Build method :
                ```
                @Override
                public void loadInto(Map<String, Class<? extends IRouteGroup>> routes)
                ```
             */
            MethodSpec.Builder loadIntoMethodOfRootBuilder = MethodSpec.methodBuilder(METHOD_LOAD_INTO)
                    .addAnnotation(Override.class)
                    .addModifiers(PUBLIC)
                    .addParameter(rootParamSpec);

            //  Follow a sequence, find out metas of group first, generate java file, then statistics them as root.
            for (Element element : routeElements) {
                // 遍历被@Route注释的类
                TypeMirror tm = element.asType();
                // 获取该类的Route注解
                Route route = element.getAnnotation(Route.class);
                RouteMeta routeMeta;

                // 分别构建Activity、Fragment、IProvider、Service的RouteMeta，其他类型不支持(报错)
                // Activity or Fragment
                if (types.isSubtype(tm, type_Activity) || types.isSubtype(tm, fragmentTm) || types.isSubtype(tm, fragmentTmV4)) {
                    // 如果该类是Activity或Fragment的子类

                    // 递归收集类中被@Autowired修饰的成员变量、变量类型、Autowired注解，
                    // 并以变量名为key，分别以变量类型、Autowired注解为value，分别放入paramsType和injectConfig中
                    // Get all fields annotation by @Autowired
                    Map<String, Integer> paramsType = new HashMap<>();
                    Map<String, Autowired> injectConfig = new HashMap<>();
                    injectParamCollector(element, paramsType, injectConfig);

                    // 构建Activity或Fragment的RouteMeta
                    if (types.isSubtype(tm, type_Activity)) {
                        // Activity
                        logger.info(">>> Found activity route: " + tm.toString() + " <<<");
                        routeMeta = new RouteMeta(route, element, RouteType.ACTIVITY, paramsType);
                    } else {
                        // Fragment
                        logger.info(">>> Found fragment route: " + tm.toString() + " <<<");
                        routeMeta = new RouteMeta(route, element, RouteType.parse(FRAGMENT), paramsType);
                    }

                    // 将类的成员遍历的@Autowired信息放入RouteMeta中
                    routeMeta.setInjectConfig(injectConfig);
                } else if (types.isSubtype(tm, iProvider)) {         // IProvider
                    logger.info(">>> Found provider route: " + tm.toString() + " <<<");
                    routeMeta = new RouteMeta(route, element, RouteType.PROVIDER, null);
                } else if (types.isSubtype(tm, type_Service)) {           // Service
                    logger.info(">>> Found service route: " + tm.toString() + " <<<");
                    routeMeta = new RouteMeta(route, element, RouteType.parse(SERVICE), null);
                } else {
                    throw new RuntimeException("The @Route is marked on unsupported class, look at [" + tm.toString() + "].");
                }

                // 将RouteMeta放入groupMap(以groupName为key，TreeSet<RouteMeta>为value
                categories(routeMeta);
            }

            /*
                构建loadInto函数：
                @Override
                public void loadInto(Map<String, RouteMeta> providers)
             */
            MethodSpec.Builder loadIntoMethodOfProviderBuilder = MethodSpec.methodBuilder(METHOD_LOAD_INTO)
                    .addAnnotation(Override.class)
                    .addModifiers(PUBLIC)
                    .addParameter(providerParamSpec);

            Map<String, List<RouteDoc>> docSource = new HashMap<>();

            // Start generate java source, structure is divided into upper and lower levels, used for demand initialization.
            // 开始生成java源文件
            for (Map.Entry<String, Set<RouteMeta>> entry : groupMap.entrySet()) {
                String groupName = entry.getKey();

                /*
                    构建loadInto函数：
                     @Override
                     public void loadInto(Map<String, RouteMeta> atlas)
                 */
                MethodSpec.Builder loadIntoMethodOfGroupBuilder = MethodSpec.methodBuilder(METHOD_LOAD_INTO)
                        .addAnnotation(Override.class)
                        .addModifiers(PUBLIC)
                        .addParameter(groupParamSpec);

                List<RouteDoc> routeDocList = new ArrayList<>();

                // Build group method body 构建方法体
                Set<RouteMeta> groupData = entry.getValue();
                for (RouteMeta routeMeta : groupData) {
                    RouteDoc routeDoc = extractDocInfo(routeMeta);

                    // @Route修饰的类的类名
                    ClassName className = ClassName.get((TypeElement) routeMeta.getRawType());

                    switch (routeMeta.getType()) {
                        case PROVIDER:  // Need cache provider's super class
                            // @Route修饰的类是个IProvider服务类
                            List<? extends TypeMirror> interfaces = ((TypeElement) routeMeta.getRawType()).getInterfaces();
                            for (TypeMirror tm : interfaces) {  // 遍历@Route修饰的类实现了的接口

                                routeDoc.addPrototype(tm.toString());

                                if (types.isSameType(tm, iProvider)) {   // Its implements iProvider interface himself.
                                    // @Route修饰的类直接 implement IProvider
                                    // This interface extend the IProvider, so it can be used for mark provider
                                    /*
                                        构建语句,
                                        eg：
                                        package com.xxx;
                                        @Route("/a/b)
                                        public class HelloService implement IProvider {}

                                        生成：
                                        providers.put("com.xxx.HelloService", RouteMeta.build(RouteType.PROVIDER, HelloService.class, "/a/b", "a", null, -1, Integer.MIN_VALUE));

                                        解释：
                                        @Route注解默认priority=-1，extras=Integer.MIN_VALUE
                                     */
                                    loadIntoMethodOfProviderBuilder.addStatement(
                                            "providers.put($S, $T.build($T." + routeMeta.getType() + ", $T.class, $S, $S, null, " + routeMeta.getPriority() + ", " + routeMeta.getExtra() + "))",
                                            (routeMeta.getRawType()).toString(),
                                            routeMetaCn,
                                            routeTypeCn,
                                            className,
                                            routeMeta.getPath(),
                                            routeMeta.getGroup());
                                } else if (types.isSubtype(tm, iProvider)) {
                                    // @Route修饰的类 implement 的接口继承了IProvider
                                    // This interface extend the IProvider, so it can be used for mark provider
                                    /*
                                        构建语句,
                                        eg：
                                        package com.xxx;
                                        public class HelloService implement IProvider {}

                                        package com.xxx;
                                        @Route("/a/b)
                                        public class HelloServiceImpl implement HelloService {}

                                        @Route("/a/b)
                                        public class AHelloServiceImpl2 implement HelloService {}

                                        理论生成：
                                        providers.put("com.xxx.HelloService", RouteMeta.build(RouteType.PROVIDER, HelloServiceImpl.class, "/a/b", "a", null, -1, Integer.MIN_VALUE));
                                        providers.put("com.xxx.HelloService", RouteMeta.build(RouteType.PROVIDER, AHelloServiceImpl2.class, "/a/b", "a", null, -1, Integer.MIN_VALUE));

                                        实际生成：
                                        providers.put("com.xxx.HelloService", RouteMeta.build(RouteType.PROVIDER, HelloServiceImpl.class, "/a/b", "a", null, -1, Integer.MIN_VALUE));

                                        解释：
                                        1. @Route注解默认priority=-1，extras=Integer.MIN_VALUE
                                        2. 之所以只生成了一条put语句，是因为在前面为AHelloServiceImpl2分类执行categories(routeMeta)方法时，
                                        AHelloServiceImpl2由于和HelloServiceImpl具有相同的group和path，
                                        根据group从groupMap中取到对应的TreeSet后，执行TreeSet.add(routeMeta)时，
                                        由于HelloServiceImpl和AHelloServiceImpl2的path是一样的，
                                        当TreeSet.add(r2)->TreeMap.put(r2, PRESENT)时，通过comparator.compare(r1, r2)返回0，
                                        所以只会更新r1的value，不会将r2放入TreeMap中
                                        所以这里遍历groupMap中的set时，set中根本就没有AHelloServiceImpl2。
                                        因此当我们通过((HelloService) ARouter.getInstance().build("/yourservicegroupname/hello").navigation()).sayHello("mike");调用服务的方法时，拿到的服务是HelloServiceImpl
                                     */
                                    logger.info(">>>>>" + className.simpleName());
                                    loadIntoMethodOfProviderBuilder.addStatement(
                                            "providers.put($S, $T.build($T." + routeMeta.getType() + ", $T.class, $S, $S, null, " + routeMeta.getPriority() + ", " + routeMeta.getExtra() + "))",
                                            tm.toString(),    // So stupid, will duplicate only save class name.
                                            routeMetaCn,
                                            routeTypeCn,
                                            className,
                                            routeMeta.getPath(),
                                            routeMeta.getGroup());
                                }
                            }
                            break;
                        default:
                            break;
                    }

                    // Make map body for paramsType
                    // 处理paramsType，生成ARouter$$Group$${groupName}中
                    // atlas.put(..., RouteMeta.build(..., paramsType, ...))的 paramsType 的 map body
                    StringBuilder mapBodyBuilder = new StringBuilder();
                    Map<String, Integer> paramsType = routeMeta.getParamsType();
                    Map<String, Autowired> injectConfigs = routeMeta.getInjectConfig();
                    if (MapUtils.isNotEmpty(paramsType)) {
                        List<RouteDoc.Param> paramList = new ArrayList<>();

                        for (Map.Entry<String, Integer> types : paramsType.entrySet()) {
                            // eg: put("name", 8)
                            mapBodyBuilder.append("put(\"").append(types.getKey()).append("\", ").append(types.getValue()).append("); ");

                            RouteDoc.Param param = new RouteDoc.Param();
                            Autowired injectConfig = injectConfigs.get(types.getKey());
                            param.setKey(types.getKey());
                            param.setType(TypeKind.values()[types.getValue()].name().toLowerCase());
                            param.setDescription(injectConfig.desc());
                            param.setRequired(injectConfig.required());

                            paramList.add(param);
                        }

                        routeDoc.setParams(paramList);
                    }
                    String mapBody = mapBodyBuilder.toString();

                    /*
                        构建语句, eg：
                        atlas.put("/test/activity1", RouteMeta.build(RouteType.ACTIVITY,
                            Test1Activity.class, "/test/activity1", "test",
                            new java.util.HashMap<String, Integer>(){{put("ser", 9); put("ch", 5); ... }},
                            -1, -2147483648));
                     */
                    loadIntoMethodOfGroupBuilder.addStatement(
                            "atlas.put($S, $T.build($T." + routeMeta.getType() + ", $T.class, $S, $S, " + (StringUtils.isEmpty(mapBody) ? null : ("new java.util.HashMap<String, Integer>(){{" + mapBodyBuilder.toString() + "}}")) + ", " + routeMeta.getPriority() + ", " + routeMeta.getExtra() + "))",
                            routeMeta.getPath(),
                            routeMetaCn,
                            routeTypeCn,
                            className,
                            routeMeta.getPath().toLowerCase(),
                            routeMeta.getGroup().toLowerCase());

                    routeDoc.setClassName(className.toString());
                    routeDocList.add(routeDoc);
                }

                // Generate groups
                /*
                    生成group文件，内部保存Activity/Fragment/IProvider相关信息: ARouter$$Group$${groupName}.java。
                    以path为key，Activity/Fragment/IProvider相关的RouteMeta为value。
                    public class ARouter$$Group$${groupName} implements IRouteGroup {
                        @Override
                        public void loadInto(Map<String, RouteMeta> atlas) {    // atlas是Warehouse.routes
                            atlas.put("/test/activity1", RouteMeta.build(RouteType.ACTIVITY,
                            Test1Activity.class, "/test/activity1", "test",
                            new java.util.HashMap<String, Integer>(){{put("ser", 9); put("ch", 5); ... }},
                            -1, -2147483648));
                        }
                    }
                 */
                String groupFileName = NAME_OF_GROUP + groupName;
                JavaFile.builder(PACKAGE_OF_GENERATE_FILE,
                        TypeSpec.classBuilder(groupFileName)
                                .addJavadoc(WARNING_TIPS)
                                .addSuperinterface(ClassName.get(type_IRouteGroup))
                                .addModifiers(PUBLIC)
                                .addMethod(loadIntoMethodOfGroupBuilder.build())
                                .build()
                ).build().writeTo(mFiler);

                logger.info(">>> Generated group: " + groupName + "<<<");
                // 将groupName和groupFileName进行关联
                rootMap.put(groupName, groupFileName);
                docSource.put(groupName, routeDocList);
            }

            if (MapUtils.isNotEmpty(rootMap)) {
                // Generate root meta by group name, it must be generated before root, then I can find out the class of group.
                for (Map.Entry<String, String> entry : rootMap.entrySet()) {
                    /*
                        构建语句（以groupName为key，ARouter$$Group$${groupName}.class为value）：
                        routes.put("test", ARouter$$Group$$test.class);
                     */
                    loadIntoMethodOfRootBuilder.addStatement("routes.put($S, $T.class)", entry.getKey(), ClassName.get(PACKAGE_OF_GENERATE_FILE, entry.getValue()));
                }
            }

            // Output route doc
            if (generateDoc) {
                docWriter.append(JSON.toJSONString(docSource, SerializerFeature.PrettyFormat));
                docWriter.flush();
                docWriter.close();
            }

            // Write provider into disk
            /*
                生成provider文件，内部保存IProvider相关信息：ARouter$$Providers$${moduleName}.java。
                以IProvider服务的实现类或其接口的全路径类名为key，实现类的RouteMeta为value。
                public class ARouter$$Providers$${moduleName} implements IProviderGroup {
                  @Override
                  public void loadInto(Map<String, RouteMeta> providers) {  // providers是Warehouse.providersIndex
                    providers.put("com.alibaba.android.arouter.demo.service.HelloService",
                        RouteMeta.build(RouteType.PROVIDER, HelloServiceImpl.class,
                        "/yourservicegroupname/hello", "yourservicegroupname", null, -1, -2147483648));
                  }
                }
             */
            String providerMapFileName = NAME_OF_PROVIDER + SEPARATOR + moduleName;
            JavaFile.builder(PACKAGE_OF_GENERATE_FILE,
                    TypeSpec.classBuilder(providerMapFileName)
                            .addJavadoc(WARNING_TIPS)
                            .addSuperinterface(ClassName.get(type_IProviderGroup))
                            .addModifiers(PUBLIC)
                            .addMethod(loadIntoMethodOfProviderBuilder.build())
                            .build()
            ).build().writeTo(mFiler);

            logger.info(">>> Generated provider map, name is " + providerMapFileName + " <<<");

            // Write root meta into disk.
            /*
                生成root文件，内部以groupName为key，实现了IRouteGroup接口的ARouter$$Group$${groupName}.class为value，
                保存了groupName和group文件之间的映射关系，方便后面查找group中的类
                public class ARouter$$Root$${moduleName} implements IRouteRoot {
                  @Override
                  public void loadInto(Map<String, Class<? extends IRouteGroup>> routes) {  // routes是Warehouse.groupsIndex
                        routes.put("test", ARouter$$Group$${groupName}.class);
                  }
                }
             */
            String rootFileName = NAME_OF_ROOT + SEPARATOR + moduleName;
            JavaFile.builder(PACKAGE_OF_GENERATE_FILE,
                    TypeSpec.classBuilder(rootFileName)
                            .addJavadoc(WARNING_TIPS)
                            .addSuperinterface(ClassName.get(elementUtils.getTypeElement(ITROUTE_ROOT)))
                            .addModifiers(PUBLIC)
                            .addMethod(loadIntoMethodOfRootBuilder.build())
                            .build()
            ).build().writeTo(mFiler);

            logger.info(">>> Generated root, name is " + rootFileName + " <<<");
        }
    }

    /**
     * Recursive inject config collector.
     * 递归收集Activity或Fragment内部被@Autowired修饰的成员变量及其注解
     * @param element current element.
     */
    private void injectParamCollector(Element element, Map<String, Integer> paramsType, Map<String, Autowired> injectConfig) {
        for (Element field : element.getEnclosedElements()) {
            // 遍历Activity或Fragment的内部元素（直接声明的成员变量、成员函数、构造函数）
            if (field.getKind().isField() && field.getAnnotation(Autowired.class) != null && !types.isSubtype(field.asType(), iProvider)) {
                // It must be field, then it has annotation, but it not be provider.
                // 如果是被@Autowired修饰的成员变量，且不是IProvider的子类型，则获取@Autowired的属性
                Autowired paramConfig = field.getAnnotation(Autowired.class);
                // 如果@Autowired没有声明name属性，则取成员变量的变量名，否则取name属性的值
                String injectName = StringUtils.isEmpty(paramConfig.name()) ? field.getSimpleName().toString() : paramConfig.name();
                // 以@Autowired的name属性的值为key，成员变量的类型对应的枚举的ordinal为value，放入paramsType中
                paramsType.put(injectName, typeUtils.typeExchange(field));
                // 以@Autowired的name属性的值为key，Autowired注解为value，放入injectConfig中
                injectConfig.put(injectName, paramConfig);
            }
        }

        // if has parent?
        TypeMirror parent = ((TypeElement) element).getSuperclass();
        if (parent instanceof DeclaredType) {
            Element parentElement = ((DeclaredType) parent).asElement();
            if (parentElement instanceof TypeElement && !((TypeElement) parentElement).getQualifiedName().toString().startsWith("android")) {
                // 如果存在父类，且父类不是android包下的类，则递归收集父类中被@Autowired修饰的成员变量
                injectParamCollector(parentElement, paramsType, injectConfig);
            }
        }
    }

    /**
     * Extra doc info from route meta
     *
     * @param routeMeta meta
     * @return doc
     */
    private RouteDoc extractDocInfo(RouteMeta routeMeta) {
        RouteDoc routeDoc = new RouteDoc();
        routeDoc.setGroup(routeMeta.getGroup());
        routeDoc.setPath(routeMeta.getPath());
        routeDoc.setDescription(routeMeta.getName());
        routeDoc.setType(routeMeta.getType().name().toLowerCase());
        routeDoc.setMark(routeMeta.getExtra());

        return routeDoc;
    }

    /**
     * Sort metas in group.
     * 将RouteMeta放入对应group的set中
     *
     * @param routeMete metas.
     */
    private void categories(RouteMeta routeMete) {
        // 验证Route注解是否合法，主要验证是否有path，path是否以/开头，是否有group(显示声明或取path中/之间的字符串)
        if (routeVerify(routeMete)) {
            logger.info(">>> Start categories, group = " + routeMete.getGroup() + ", path = " + routeMete.getPath() + " <<<");

            // 取出同group的RouteMeta的集合
            boolean ret = false;
            Set<RouteMeta> routeMetas = groupMap.get(routeMete.getGroup());
            if (CollectionUtils.isEmpty(routeMetas)) {
                // 如果该group还没有创建过set，则创建TreeSet(以RouteMeta中的path进行字典序排序)
                // TreeSet内部使用TreeMap进行数据存储，TreeSet.add(data)的data作为TreeMap的key，value是个固定的Object(PRESENT)。
                // TreeMap内部会使用 TreeSet 的 Comparator 与 TreeMap 中的数据进行比较，如果 Comparator.compare(r1,r2) 返回 0，则说明 r1=r2，
                // 此时r2(TreeSet之后add的数据)不会被添加进TreeMap，而是更新r1(TreeSet之前add的数据)的value
                routeMetas = new TreeSet<>(new Comparator<RouteMeta>() {
                    @Override
                    public int compare(RouteMeta r1, RouteMeta r2) {
                        try {
                            return r1.getPath().compareTo(r2.getPath());
                        } catch (NullPointerException npe) {
                            logger.error(npe.getMessage());
                            return 0;
                        }
                    }
                });
                // 将RouteMeta放入set中
                ret = routeMetas.add(routeMete);
                // 将set和groupName进行关联
                groupMap.put(routeMete.getGroup(), routeMetas);
            } else {
                ret = routeMetas.add(routeMete);
            }
            logger.info(">>> end categories: " + ret);
            logger.info(">>> end categories, set's size is " + routeMetas.size());
            routeMetas.forEach(routeMeta -> {
                logger.info(">>> end categories:" + routeMeta.getPath() + " -- " + routeMeta.getRawType().getSimpleName());
            });
        } else {
            logger.warning(">>> Route meta verify error, group is " + routeMete.getGroup() + " <<<");
        }
    }

    /**
     * Verify the route meta
     * 验证Route注解是否合法，主要验证是否有path，path是否以/开头，是否有group(显示声明或取path中/之间的字符串)
     * @param meta raw meta
     */
    private boolean routeVerify(RouteMeta meta) {
        String path = meta.getPath();

        if (StringUtils.isEmpty(path) || !path.startsWith("/")) {   // The path must be start with '/' and not empty!
            return false;
        }

        if (StringUtils.isEmpty(meta.getGroup())) { // Use default group(the first word in path)
            try {
                String defaultGroup = path.substring(1, path.indexOf("/", 1));
                if (StringUtils.isEmpty(defaultGroup)) {
                    return false;
                }

                meta.setGroup(defaultGroup);
                return true;
            } catch (Exception e) {
                logger.error("Failed to extract default group! " + e.getMessage());
                return false;
            }
        }

        return true;
    }
}
