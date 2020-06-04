package com.lagou.edu.factory;

import com.lagou.edu.annotation.*;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 应癫
 *
 * 工厂类，生产对象（使用反射技术）
 */
public class BeanFactory {

    //集合的定义放在类的最上面来，因为后面的使用时在static静态代码块中，否则在使用时集合对象会为null
    static List<String> classNames = new ArrayList<>();    // 缓存扫描到的class全限定类名
    static List<String> fieldsAlreayProcessed = new ArrayList<>(); // 缓存已经进行过依赖注入的信息

    /**
     * 任务一：读取解析xml，通过反射技术实例化对象并且存储待用（map集合）
     * 任务二：对外提供获取实例对象的接口（根据id获取）
     */
    private static Map<String,Object> map = new HashMap<>();  // 存储对象


    static {
        // 任务一：扫描注解，通过反射实例化对象）
        // 加载xml
        InputStream resourceAsStream = BeanFactory.class.getClassLoader().getResourceAsStream("beans.xml");
        // 解析xml
        SAXReader saxReader = new SAXReader();
        try {
            Document document = saxReader.read(resourceAsStream);
            Element rootElement = document.getRootElement();
            Element scanElement = (Element) rootElement.selectSingleNode("//component-scan");
            String scanPackage = scanElement.attributeValue("base-package");

            System.out.println("扫描的包路径："+scanPackage);
            // 扫描注解
            doScan(scanPackage);
            // 实例化
            doInstance();
            // 维护依赖注入关系
            doAutoWired();
            // 维护事务
            doTransactional();


        } catch (DocumentException e) {
            e.printStackTrace();
        }

    }


    /**
     * 扫描指定包下的注解
     *
     * 1.扫描绝对路径：/D:/LagouTransferProject/target/classes/com/lagou/edu下的所有字节码，并去除.class
     * 2.存储到classNames中（包路径）
     */
    private static void doScan(String scanPackage) {
        //拼接绝对路径，方便构建File对象，利用扫描
        String scanPackagePath = Thread.currentThread().getContextClassLoader().
                getResource("").getPath() + scanPackage.replaceAll("\\.", "/");

        System.out.println("scanPackagePath："+scanPackagePath);
        File pack = new File(scanPackagePath);

        File[] files = pack.listFiles();

        for(File file: files) {
            if(file.isDirectory()) { // 子package
                // 递归
                doScan(scanPackage + "." + file.getName());  // com.lagou.demo.controller
            }else if(file.getName().endsWith(".class")) {
                //去除.class后缀
                //存储数据到集合中
                String className = scanPackage + "." + file.getName().replaceAll(".class", "");
                classNames.add(className);
            }
        }
    }


    /**
     * 通过反射实例化对象，此时暂不维护依赖注入关系
     */
    private static void doInstance() {
        if (classNames.size() == 0) return;

        try {

            for (int i = 0; i < classNames.size(); i++) {
                String className = classNames.get(i);
                if(className.contains("servlet")){
                    continue;
                }
                // 反射
                Class<?> aClass = Class.forName(className);
                // 1.判断标注
                // 只处理标注了注解@MyService、@MyRepository和@MyComponent的类
                if (aClass.isAnnotationPresent(MyService.class)
                        || aClass.isAnnotationPresent(MyRepository.class)
                        || aClass.isAnnotationPresent(MyComponent.class)) {

                    //2.获取注解value值
                    String beanName = null;

                    if (aClass.isAnnotationPresent(MyService.class)) {
                        beanName = aClass.getAnnotation(MyService.class).value();
                    } else if (aClass.isAnnotationPresent(MyRepository.class)) {
                        beanName = aClass.getAnnotation(MyRepository.class).value();
                    } else if (aClass.isAnnotationPresent(MyComponent.class)) {
                        beanName = aClass.getAnnotation(MyComponent.class).value();
                    }

                    //判断是否指定的id属性（实例化了两个对象到bean工厂）
                    // 如果指定了id，就以指定的为准
                    Object o = aClass.newInstance();
                    if ("".equals(beanName.trim())) {
                        beanName = lowerFirst(aClass.getSimpleName());
                    }
                    map.put(beanName,o);


                    // service层往往是有接口的，面向接口开发，此时再以接口名为id，放入一份对象到容器中，便于后期根据接口类型注入
                    Class<?>[] interfaces = aClass.getInterfaces();
                    if(interfaces != null && interfaces.length > 0) {
                        for (int j = 0; j < interfaces.length; j++) {
                            Class<?> anInterface = interfaces[j];
                            // 以接口的全限定类名作为id放入
                            //System.out.println("已经实例化到工厂中的bean："+anInterface.getName()+"/"+aClass.newInstance());
                            map.put(anInterface.getName(), aClass.newInstance());
                        }
                    }
                } else {
                    continue;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /**
     * 首字母小写方法
     */
    private static String lowerFirst(String str) {
        char[] chars = str.toCharArray();
        if('A' <= chars[0] && chars[0] <= 'Z') {
            chars[0] += 32;
        }
        return String.valueOf(chars);
    }


    /*
     * 实现依赖注入
     */
    private static void doAutoWired(){
        if(map.isEmpty()) {return;}

        // 遍历ioc中所有对象，查看对象中的字段，是否有@LagouAutowired注解，如果有需要维护依赖注入关系
        for(Map.Entry<String,Object> entry: map.entrySet()) {
            try {
                doObjectDependancy(entry.getValue());
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * A 可能依赖于 B ，B 可能依赖于 C ，C 可能又依赖于D，本方法主要维护一下嵌套依赖
     */
    private static void doObjectDependancy(Object object) throws IllegalAccessException {
        //1.反射的方式，获取对象所有属性
        Field[] declaredFields = object.getClass().getDeclaredFields();

        if(declaredFields == null || declaredFields.length ==0) {
            return;
        }
        // 遍历判断处理
        for (int i = 0; i < declaredFields.length; i++) {

            Field declaredField = declaredFields[i];

            if (!declaredField.isAnnotationPresent(MyAutowired.class)) {
                continue;
            }


            // 判断当前字段是否处理过，如果已经处理过则continue，避免嵌套处理死循环
            if(fieldsAlreayProcessed.contains(object.getClass().getName()  + "." + declaredField.getName())){
                continue;
            }

            //2.获取属性对象，类名称  或者  首字母小写的累名称
            //从bean容器中获取被依赖的属性对象
            Object dependObject = null;
            dependObject = map.get(declaredField.getType().getName());  //  先按照声明的是接口去获取，如果获取不到再按照首字母小写

            if(dependObject == null) {
                dependObject = map.get(lowerFirst(declaredField.getType().getSimpleName()));
            }


            //3. 记录下给哪个对象的哪个属性设置过，避免死循环
            // 将当前属性对象添加到容器中，避免重复
            fieldsAlreayProcessed.add(object.getClass().getName() + "." + declaredField.getName());

            // 迭代
            doObjectDependancy(dependObject);


            //4. 反射的方式，设置属性值，declaredFile当前属性对象，object当前类对象，被依赖的dependObject 属性对象
            declaredField.setAccessible(true);
            declaredField.set(object,dependObject);

        }
    }




    /*
     * 实现事务管理，为添加了@MyTransactional注解的对象创建代理对象，并覆盖原IOC容器中的对象
     */
    private static void doTransactional() {
        ProxyFactory proxyFactory = (ProxyFactory) map.get("proxyFactory");

        //1.遍历所有的bean对象，判断是否增强
        for(Map.Entry<String,Object> entry: map.entrySet()) {
            String beanName = entry.getKey();
            Object o = entry.getValue();
            Class<?> aClass = entry.getValue().getClass();
            if(aClass.isAnnotationPresent(MyTransactional.class)) {
                // 需要进行事务控制
                //2.使用代理对象，替换原对象
                // 有实现接口
                Class<?>[] interfaces = aClass.getInterfaces();
                if(interfaces != null && interfaces.length > 0) {
                    // 使用jdk动态代理
                    map.put(beanName,proxyFactory.getJdkProxy(o));
                }else{
                    // 使用cglib动态代理
                    map.put(beanName,proxyFactory.getCglibProxy(o));
                }
            }
        }

    }














    // 任务二：对外提供获取实例对象的接口（根据id获取）
    public static  Object getBean(String id) {
        return map.get(id);
    }









    public static void main(String[] args) {
        //1.测试扫描类
        BeanFactory beanFactory = new BeanFactory();
        for (String name : beanFactory.classNames) {
            System.out.println(name);
        }
        System.out.println("bean对象实例化：-----------------------");
        //2.测试实例化注解类
        for (Map.Entry<String, Object> stringObjectEntry : beanFactory.map.entrySet()) {
            System.out.println("实例化到bean工厂的对象："+stringObjectEntry.getKey()+"/"+stringObjectEntry.getValue());
        }

        System.out.println("依赖注入：-----------------------");
        for (String s : fieldsAlreayProcessed) {
            System.out.println(s);
        }
    }
}
