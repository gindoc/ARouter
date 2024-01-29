package com.alibaba.android.arouter.compiler.processor;

import com.alibaba.android.arouter.compiler.utils.Consts;
import com.alibaba.android.arouter.facade.annotation.Autowired;
import com.alibaba.android.arouter.facade.enums.TypeKind;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import static com.alibaba.android.arouter.compiler.utils.Consts.ANNOTATION_TYPE_AUTOWIRED;
import static com.alibaba.android.arouter.compiler.utils.Consts.ISYRINGE;
import static com.alibaba.android.arouter.compiler.utils.Consts.JSON_SERVICE;
import static com.alibaba.android.arouter.compiler.utils.Consts.METHOD_INJECT;
import static com.alibaba.android.arouter.compiler.utils.Consts.NAME_OF_AUTOWIRED;
import static com.alibaba.android.arouter.compiler.utils.Consts.TYPE_WRAPPER;
import static com.alibaba.android.arouter.compiler.utils.Consts.WARNING_TIPS;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * Processor used to create autowired helper
 *
 * @author zhilong <a href="mailto:zhilong.lzl@alibaba-inc.com">Contact me.</a>
 * @version 1.0
 * @since 2017/2/20 下午5:56
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({ANNOTATION_TYPE_AUTOWIRED})
public class AutowiredProcessor extends BaseProcessor {

    /**
     * 以目标类为key，目标类中待注入的变量为value
     */
    private Map<TypeElement, List<Element>> parentAndChild = new HashMap<>();   // Contain field need autowired and his super class.
    private static final ClassName ARouterClass = ClassName.get("com.alibaba.android.arouter.launcher", "ARouter");
    private static final ClassName AndroidLog = ClassName.get("android.util", "Log");

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);

        logger.info(">>> AutowiredProcessor init. <<<");
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        if (CollectionUtils.isNotEmpty(set)) {
            try {
                logger.info(">>> Found autowired field, start... <<<");
                // 获取@Autowired修饰的元素，并进行以它的父元素为key，对应的集合为value，进行分类，放入parentAndChild中
                categories(roundEnvironment.getElementsAnnotatedWith(Autowired.class));

                // 生成辅助类${target class simpleName}$$ARouter$$Autowired，为目标类@Autowired修饰的成员变量注入变量值
                generateHelper();

            } catch (Exception e) {
                logger.error(e);
            }
            return true;
        }

        return false;
    }

    /**
     * 生成辅助类${target class simpleName}$$ARouter$$Autowired，为目标类@Autowired修饰的成员变量注入变量值
     */
    private void generateHelper() throws IOException, IllegalAccessException {
        TypeElement type_ISyringe = elementUtils.getTypeElement(ISYRINGE);
        TypeElement type_JsonService = elementUtils.getTypeElement(JSON_SERVICE);
        TypeMirror iProvider = elementUtils.getTypeElement(Consts.IPROVIDER).asType();
        TypeMirror activityTm = elementUtils.getTypeElement(Consts.ACTIVITY).asType();
        TypeMirror fragmentTm = elementUtils.getTypeElement(Consts.FRAGMENT).asType();
        TypeMirror fragmentTmV4 = elementUtils.getTypeElement(Consts.FRAGMENT_V4).asType();

        // Build input param name.
        // 构建输入参数：Object target
        ParameterSpec objectParamSpec = ParameterSpec.builder(TypeName.OBJECT, "target").build();

        if (MapUtils.isNotEmpty(parentAndChild)) {
            for (Map.Entry<TypeElement, List<Element>> entry : parentAndChild.entrySet()) {
                /*
                 * Build method : 'inject'
                 * 构建方法：
                 * @Override
                 * public void inject(Object target)
                 */
                MethodSpec.Builder injectMethodBuilder = MethodSpec.methodBuilder(METHOD_INJECT)
                        .addAnnotation(Override.class)
                        .addModifiers(PUBLIC)
                        .addParameter(objectParamSpec);

                // 父类，即待注入变量的目标类，下面以Test1Activity为例
                TypeElement parent = entry.getKey();
                // 目标类中待注入的变量
                List<Element> childs = entry.getValue();

                // 获取目标类的全限定类名
                String qualifiedName = parent.getQualifiedName().toString();
                // 获取目标类的包名
                String packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf("."));
                // 生成的类名，用于为目标类注入变量
                String fileName = parent.getSimpleName() + NAME_OF_AUTOWIRED;

                logger.info(">>> Start process " + childs.size() + " field in " + parent.getSimpleName() + " ... <<<");

                /*
                 * 构建目标类，实现了ISyringe接口：
                 * public class ${target class simpleName}$$ARouter$$Autowired implements ISyringe {}
                 * 例如：
                 * public class Test1Activity$$ARouter$$Autowired implements ISyringe {}
                 */
                TypeSpec.Builder helper = TypeSpec.classBuilder(fileName)
                        .addJavadoc(WARNING_TIPS)
                        .addSuperinterface(ClassName.get(type_ISyringe))
                        .addModifiers(PUBLIC);

                /*
                 * 为目标构建私有属性：
                 * private SerializationService serializationService;
                 */
                FieldSpec jsonServiceField = FieldSpec.builder(TypeName.get(type_JsonService.asType()), "serializationService", Modifier.PRIVATE).build();
                helper.addField(jsonServiceField);

                /*
                 * 为 public void inject(Object target) 添加语句：
                 * serializationService = ARouter.getInstance().navigation(SerializationService.class);
                 * Test1Activity substitute = (Test1Activity)target
                 */
                injectMethodBuilder.addStatement("serializationService = $T.getInstance().navigation($T.class)", ARouterClass, ClassName.get(type_JsonService));
                injectMethodBuilder.addStatement("$T substitute = ($T)target", ClassName.get(parent), ClassName.get(parent));

                // Generate method body, start inject.
                // 开始注入变量值
                for (Element element : childs) {
                    Autowired fieldConfig = element.getAnnotation(Autowired.class);
                    // 变量名
                    String fieldName = element.getSimpleName().toString();
                    if (types.isSubtype(element.asType(), iProvider)) {  // It's provider
                        // 如果变量是IProvider类型
                        if ("".equals(fieldConfig.name())) {    // User has not set service path, then use byType.
                            /*
                             * 用户没有为@Autowired设置name属性，则通过ARouter的navigation(Class<? extends T> service)方法获取服务
                             * Getter
                             * 例如：
                             * @Route(path = "/test/activity1", name = "测试用 Activity")
                             * public class Test1Activity extends BaseActivity {
                             *      @Autowired
                             *      HelloService helloService;
                             *      ...
                             * }
                             *
                             * public interface HelloService extends IProvider {}
                             *
                             * @Route(path = "/yourservicegroupname/hello")
                             * public class HelloServiceImpl implements HelloService {}
                             *
                             * 则生成代码如下：
                             * substitute.helloService = ARouter.getInstance().navigation(HelloService.class);
                             */
                            injectMethodBuilder.addStatement(
                                    "substitute." + fieldName + " = $T.getInstance().navigation($T.class)",
                                    ARouterClass,
                                    ClassName.get(element.asType())
                            );
                        } else {    // use byName
                            /*
                             * 如果用户设置了@Autowired的name属性，则通过ARouter的
                             * build(String path).navigation()方法获取服务，并强转成成员变量类型
                             * Getter
                             * 例如：
                             * @Route(path = "/test/activity1", name = "测试用 Activity")
                             * public class Test1Activity extends BaseActivity {
                             *      @Autowired(name = "/yourservicegroupname/ahello")
                             *      HelloService helloService1;
                             *      ...
                             * }
                             *
                             * public interface HelloService extends IProvider {}
                             *
                             * @Route(path = "/yourservicegroupname/ahello")
                             * public class AHelloServiceImpl2 implements HelloService {}
                             *
                             * 则生成代码如下：
                             * substitute.helloService1 = (HelloService)ARouter.getInstance().build("/yourservicegroupname/ahello").navigation();
                             */
                            injectMethodBuilder.addStatement(
                                    "substitute." + fieldName + " = ($T)$T.getInstance().build($S).navigation()",
                                    ClassName.get(element.asType()),
                                    ARouterClass,
                                    fieldConfig.name()
                            );
                        }

                        // Validator
                        if (fieldConfig.required()) {
                            // 如果@Autowired注解声明了required属性，则添加语句检查变量是否被赋值成功，如果没赋值成功，则抛异常
                            injectMethodBuilder.beginControlFlow("if (substitute." + fieldName + " == null)");
                            injectMethodBuilder.addStatement(
                                    "throw new RuntimeException(\"The field '" + fieldName + "' is null, in class '\" + $T.class.getName() + \"!\")", ClassName.get(parent));
                            injectMethodBuilder.endControlFlow();
                        }
                    } else {    // It's normal intent value
                        // 如果不是IProvider类型变量，则Activity通过getIntent()获取数据，Fragment通过getArguments()获取数据

                        /*
                         * 原始数据，在声明成员变量时就赋值了，
                         * 例如：private int age = 1，则原始数据substitute.age等于1
                         */
                        String originalValue = "substitute." + fieldName;

                        /*
                         * 例如：
                         * @Route(path = "/test/activity1", name = "测试用 Activity")
                         * public class Test1Activity extends BaseActivity {
                         *      @Autowired
                         *      int age = 1;
                         *
                         *      @Autowired
                         *      TestSerializable ser;   // TestSerializable是Serializable类型
                         *      ...
                         * }
                         * 构建语句：
                         * substitute.age = substitute.
                         * substitute.ser = (xxx.yyy.TestSerializable) substitute.
                         *
                         * ps：这里可以知道为啥@Autowired修饰的变量不能用private修饰符了，因为如果是private的就无法访问了
                         */
                        String statement = "substitute." + fieldName + " = " + buildCastCode(element) + "substitute.";
                        boolean isActivity = false;
                        if (types.isSubtype(parent.asType(), activityTm)) {  // Activity, then use getIntent()
                            // 如果目标类是Activity，则通过getIntent()获取变量值
                            isActivity = true;
                            statement += "getIntent().";
                        } else if (types.isSubtype(parent.asType(), fragmentTm) || types.isSubtype(parent.asType(), fragmentTmV4)) {   // Fragment, then use getArguments()
                            // 如果目标类是Fragment，则通过getArguments()获取变量值
                            statement += "getArguments().";
                        } else {
                            throw new IllegalAccessException("The field [" + fieldName + "] need autowired from intent, its parent must be activity or fragment!");
                        }

                        /*
                         * 为目标类@Autowired修饰的成员变量的赋值构建语句
                         * 例如：
                         * @Route(path = "/test/activity1", name = "测试用 Activity")
                         * public class Test1Activity extends BaseActivity {
                         *      @Autowired
                         *      int age = 1;
                         *
                         *      @Autowired
                         *      String name = "";
                         *
                         *      @Autowired
                         *      TestSerializable ser;   // TestSerializable是Serializable类型
                         *
                         *      @Autowired
                         *      TestObj obj;
                         *      ...
                         * }
                         * 构建语句，statement如下（参数填充后）：
                         * Activity：
                         * substitute.age = substitute.getIntent().getIntExtra("age", substitute.age)
                         * substitute.name = substitute.getIntent().getExtras() == null ? substitute.name : substitute.getIntent().getExtras().getString("name", substitute.name)
                         * substitute.ser = (xxx.yyy.TestSerializable) substitute.substitute.getIntent().getSerializableExtra("ser")
                         * serializationService.parseObject(substitute.getIntent().getStringExtra("obj"), new com.alibaba.android.arouter.facade.model.TypeWrapper<TestObj>(){}.getType())
                         *
                         * Fragment：
                         * substitute.age = substitute.getArguments().getInt("age", substitute.age)
                         * substitute.name = substitute.getArguments().getString("name", substitute.name)
                         * substitute.ser = (xxx.yyy.TestSerializable) substitute.substitute.getArguments().getSerializable("ser")
                         * serializationService.parseObject(substitute.getArguments().getString("obj"), new com.alibaba.android.arouter.facade.model.TypeWrapper<TestObj>(){}.getType())
                         */
                        statement = buildStatement(originalValue, statement, typeUtils.typeExchange(element), isActivity, isKtClass(parent));
                        if (statement.startsWith("serializationService.")) {   // Not mortals
                            /*
                             * 如果是Object类型变量，则判断serializationService是否为null，如果为null则log，否则构建语句：
                             * 例如：
                             * @Route(path = "/test/activity1", name = "测试用 Activity")
                             * public class Test1Activity extends BaseActivity {
                             *      @Autowired
                             *      TestObj obj;    // TestObj是个普通类
                             *      ...
                             * }
                             * 构建语句：
                             * if (null != serializationService) {
                             *      substitute.obj = serializationService.parseObject(substitute.getIntent().getStringExtra("obj"), new com.alibaba.android.arouter.facade.model.TypeWrapper<TestObj>(){}.getType());
                             * } else {
                             *      Log.e("ARouter::", "You want automatic inject the field 'obj' in class 'Test1Activity' , then you should implement 'SerializationService' to support object auto inject!");
                             * }
                             */
                            injectMethodBuilder.beginControlFlow("if (null != serializationService)");
                            injectMethodBuilder.addStatement(
                                    "substitute." + fieldName + " = " + statement,
                                    (StringUtils.isEmpty(fieldConfig.name()) ? fieldName : fieldConfig.name()),
                                    ClassName.get(element.asType())
                            );
                            injectMethodBuilder.nextControlFlow("else");
                            injectMethodBuilder.addStatement(
                                    "$T.e(\"" + Consts.TAG + "\", \"You want automatic inject the field '" + fieldName + "' in class '$T' , then you should implement 'SerializationService' to support object auto inject!\")", AndroidLog, ClassName.get(parent));
                            injectMethodBuilder.endControlFlow();
                        } else {
                            // 不是Object类型变量，构建语句上面展示了（参数填充后）
                            injectMethodBuilder.addStatement(statement, StringUtils.isEmpty(fieldConfig.name()) ? fieldName : fieldConfig.name());
                        }

                        // Validator
                        if (fieldConfig.required() && !element.asType().getKind().isPrimitive()) {  // Primitive wont be check.
                            // 如果不是基础数据类型，且@Autowired声明了required属性，
                            // 则添加语句检查变量是否被赋值成功，如果没赋值成功，则log
                            injectMethodBuilder.beginControlFlow("if (null == substitute." + fieldName + ")");
                            injectMethodBuilder.addStatement(
                                    "$T.e(\"" + Consts.TAG + "\", \"The field '" + fieldName + "' is null, in class '\" + $T.class.getName() + \"!\")", AndroidLog, ClassName.get(parent));
                            injectMethodBuilder.endControlFlow();
                        }
                    }
                }

                // 方法体语句添加完成后，构建方法，再将方法放入类中
                helper.addMethod(injectMethodBuilder.build());

                /*
                 * Generate autowire helper
                 * 在对应包名下构建类${target class simpleName}$$ARouter$$Autowired
                 */
                JavaFile.builder(packageName, helper.build()).build().writeTo(mFiler);

                logger.info(">>> " + parent.getSimpleName() + " has been processed, " + fileName + " has been generated. <<<");
            }

            logger.info(">>> Autowired processor stop. <<<");
        }
    }

    private boolean isKtClass(Element element) {
        for (AnnotationMirror annotationMirror : elementUtils.getAllAnnotationMirrors(element)) {
            if (annotationMirror.getAnnotationType().toString().contains("kotlin")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 如果是Serializable类型的变量，则添加强转类型语句
     */
    private String buildCastCode(Element element) {
        if (typeUtils.typeExchange(element) == TypeKind.SERIALIZABLE.ordinal()) {
            return CodeBlock.builder().add("($T) ", ClassName.get(element.asType())).build().toString();
        }
        return "";
    }

    /**
     * Build param inject statement
     * 为目标类@Autowired修饰的成员变量的赋值构建语句
     * 如果是基础数据类型 或 String 或 Serializable 或 Parcelable，
     * 则Activity通过getIntent()获取数据，Fragment通过getArguments()获取数据；
     * 如果是Object类型的变量，则通过getIntent()或getArguments()获取到字符串后，再通过SerializationService转换成对应的类型
     */
    private String buildStatement(String originalValue, String statement, int type, boolean isActivity, boolean isKt) {
        switch (TypeKind.values()[type]) {
            case BOOLEAN:
                statement += "getBoolean" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case BYTE:
                statement += "getByte" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case SHORT:
                statement += "getShort" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case INT:
                statement += "getInt" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case LONG:
                statement += "getLong" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case CHAR:
                statement += "getChar" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case FLOAT:
                statement += "getFloat" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case DOUBLE:
                statement += "getDouble" + (isActivity ? "Extra" : "") + "($S, " + originalValue + ")";
                break;
            case STRING:
                statement += (isActivity ? ("getExtras() == null ? " + originalValue + " : substitute.getIntent().getExtras().getString($S") : ("getString($S")) + ", " + originalValue + ")";
                break;
            case SERIALIZABLE:
                statement += (isActivity ? ("getSerializableExtra($S)") : ("getSerializable($S)"));
                break;
            case PARCELABLE:
                statement += (isActivity ? ("getParcelableExtra($S)") : ("getParcelable($S)"));
                break;
            case OBJECT:
                // 如果是Object类型的变量，则通过getIntent()或getArguments()获取到字符串后，
                // 再通过SerializationService转换成对应的类型
                statement = "serializationService.parseObject(substitute." + (isActivity ? "getIntent()." : "getArguments().") + (isActivity ? "getStringExtra($S)" : "getString($S)") + ", new " + TYPE_WRAPPER + "<$T>(){}.getType())";
                break;
        }

        return statement;
    }

    /**
     * 对@Autowired修饰的元素进行分类，放入它的父元素对应的集合中
     * Categories field, find his papa.
     *
     * @param elements Field need autowired
     */
    private void categories(Set<? extends Element> elements) throws IllegalAccessException {
        if (CollectionUtils.isNotEmpty(elements)) {
            for (Element element : elements) {
                // 获取element的父元素
                TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

                if (element.getModifiers().contains(Modifier.PRIVATE)) {
                    // @Autowired修饰的元素不能使用private修饰符
                    throw new IllegalAccessException("The inject fields CAN NOT BE 'private'!!! please check field ["
                            + element.getSimpleName() + "] in class [" + enclosingElement.getQualifiedName() + "]");
                }

                if (parentAndChild.containsKey(enclosingElement)) { // Has categries
                    // 父元素对应的集合已经存在，则直接放入集合中
                    parentAndChild.get(enclosingElement).add(element);
                } else {
                    // 父元素对应的集合不存在，则创建集合，存入元素，并将集合以父元素为key，集合为value，放入parentAndChild中
                    List<Element> childs = new ArrayList<>();
                    childs.add(element);
                    parentAndChild.put(enclosingElement, childs);
                }
            }

            logger.info("categories finished.");
        }
    }
}
