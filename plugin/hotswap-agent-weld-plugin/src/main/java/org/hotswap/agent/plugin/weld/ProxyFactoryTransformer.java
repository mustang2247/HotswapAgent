package org.hotswap.agent.plugin.weld;

import org.hotswap.agent.annotation.OnClassLoadEvent;
import org.hotswap.agent.config.PluginManager;
import org.hotswap.agent.javassist.CannotCompileException;
import org.hotswap.agent.javassist.ClassPool;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.CtConstructor;
import org.hotswap.agent.javassist.CtMethod;
import org.hotswap.agent.javassist.CtNewMethod;
import org.hotswap.agent.javassist.NotFoundException;
import org.hotswap.agent.javassist.expr.ExprEditor;
import org.hotswap.agent.javassist.expr.MethodCall;
import org.hotswap.agent.plugin.weld.command.ProxyClassLoadingDelegate;
import org.hotswap.agent.util.PluginManagerInvoker;

/**
 * Hook into ProxyFactory constructors to register proxy factory into WeldPlugin. If WeldPlugin is not initialized in proxy factory
 * moduleClassLoader then proxy factory is not registered. It happens most likely for system proxy factories.
 *
 * @author Vladimir Dvorak
 */
public class ProxyFactoryTransformer {

    /**
     * Patch ProxyFactory class.
     *   - add factory registration into constructor
     *   - add recreateProxyClass method
     *   - changes call ClassFileUtils.toClass() in createProxyClass() to ProxyClassLoadingDelegate.loadClass(...)
     * @param ctClass the ProxyFactory class
     */
    @OnClassLoadEvent(classNameRegexp = "org.jboss.weld.bean.proxy.ProxyFactory")
    public static void patchProxyFactory(CtClass ctClass, ClassPool classPool) throws NotFoundException, CannotCompileException {

        CtClass[] constructorParams = new CtClass[] {
            classPool.get("java.lang.String"),
            classPool.get("java.lang.Class"),
            classPool.get("java.util.Set"),
            classPool.get("javax.enterprise.inject.spi.Bean"),
            classPool.get("boolean")
        };

        CtConstructor declaredConstructor = ctClass.getDeclaredConstructor(constructorParams);

        // TODO : we should find constructor without this() call and put registration only into this one
        declaredConstructor.insertAfter(
            "if (" + PluginManager.class.getName() + ".getInstance().isPluginInitialized(\"" + WeldPlugin.class.getName() + "\", this.classLoader)) {" +
                PluginManagerInvoker.buildCallPluginMethod("this.classLoader", WeldPlugin.class, "registerProxyFactory",
                        "this", "java.lang.Object",
                        "bean", "java.lang.Object",
                        "this.classLoader", "java.lang.ClassLoader"
                        ) +
            "}"
        );

        CtMethod recreateMethod = CtNewMethod.make(
                "public void __recreateProxyClass() {" +
                "   String suffix = \"_$$_Weld\" + getProxyNameSuffix();" +
                "   String proxyClassName = getBaseProxyName();"+
                "   if (!proxyClassName.endsWith(suffix)) {" +
                "       proxyClassName = proxyClassName + suffix;" +
                "   }"+
                "   if (proxyClassName.startsWith(JAVA)) {" +
                "       proxyClassName = proxyClassName.replaceFirst(JAVA, \"org.jboss.weld\");"+
                "   }"+
                "   try {" +
                "       " + ProxyClassLoadingDelegate.class.getName() + ".beginProxyRegeneration();" +
                "       createProxyClass(proxyClassName);"+
                "   } finally {" +
                "       " + ProxyClassLoadingDelegate.class.getName() + ".endProxyRegeneration();" +
                "   }" +
                "}"
                , ctClass);

        ctClass.addMethod(recreateMethod);

        CtMethod createProxyClassMethod = ctClass.getDeclaredMethod("createProxyClass");
        createProxyClassMethod.instrument(
                new ExprEditor() {
                    public void edit(MethodCall m) throws CannotCompileException
                    {
                        if (m.getClassName().equals("org.jboss.weld.util.bytecode.ClassFileUtils") && m.getMethodName().equals("toClass"))
                            m.replace("{ $_ = org.hotswap.agent.plugin.weld.command.ProxyClassLoadingDelegate.toClass($$); }");
                    }
                });
    }

}
