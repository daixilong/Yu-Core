package com.IceCreamQAQ.Yu.hook;

import com.IceCreamQAQ.Yu.annotation.HookBy;
import com.IceCreamQAQ.Yu.loader.AppClassloader;
import com.IceCreamQAQ.Yu.util.IO;
import lombok.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.objectweb.asm.Opcodes.*;

public class YuHook {

    private static List<HookItem> hooks = new ArrayList();

    public static void put(HookItem item) {
        hooks.add(item);
    }

    public static List<HookItem> getHooks() {
        return hooks;
    }

    public static HookRunnable[] r;

    private static final Map<String, HookRunnable> hs = new HashMap<>();
    private static final Map<String, Map<String, HookInvokerRunnable>> his = new HashMap<>();
    private static final Map<Pattern, String> mhi = new HashMap<Pattern, String>();

    private static AppClassloader classloader;

    public static void putMatchHookItem(String regex, String runnable) {
        if (regex.startsWith("\\")) regex = regex.substring(1, regex.length() - 1);
        else {
            regex = regex.replace(".", "\\.");
            regex = regex.replace("?", ".");
            regex = regex.replace("*", ".*");
        }
        mhi.put(Pattern.compile(regex), runnable);
    }

    public static List<HookRunnable> getRunnables() {
        return new ArrayList<HookRunnable>() {{
            addAll(hs.values());
        }};
    }

    public static void init(AppClassloader classloader) {
        YuHook.classloader = classloader;

        for (HookItem hook : hooks) {
            getInvoker(hook.getClassName(), hook.getMethodName()).put(getOrNewRunnable(hook.getRunnable()));
        }
    }

    public static HookInvokerRunnable getInvoker(String className, String methodName) {
        val chs = his.computeIfAbsent(className, k -> new HashMap<>());
        return chs.computeIfAbsent(methodName, k -> new HookInvokerRunnable());
    }

    private static HookRunnable getOrNewRunnable(String className) {
        var runnable = hs.get(className);
        if (runnable != null) return runnable;
        try {
            runnable = (HookRunnable) Class.forName(className, true, classloader).newInstance();
            hs.put(className, runnable);
            return runnable;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("YuHook 初始化异常，无法实例化 Hook 监听类：" + className, e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("YuHook 初始化异常，找不到 Hook 监听类：" + className, e);
        }
    }

    public static byte[] checkClass(String name, byte[] bytes) {
        val ch = his.get(name);

        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);


        val newMethods = new HashMap<String, MethodNode>();

        for (val method : (List<MethodNode>) node.methods) {
            if (isSysMethod(method.name)) continue;

            boolean h = false;
            boolean mh = false;
            boolean ah = false;

            if (ch != null) {
                if (ch.containsKey(method.name)) h = true;
            }

            val cm = name + "." + method.name;
            for (val entry : mhi.entrySet()) {
                if (entry.getKey().matcher(cm).matches()) {
                    mh = true;
                    getInvoker(name, method.name).put(getOrNewRunnable(entry.getValue()));
                }
            }

            val ans = (List<AnnotationNode>) method.visibleAnnotations;
            if (ans != null) {
                for (AnnotationNode an : ans) {
                    val annotationClassName = an.desc.substring(1, an.desc.length() - 1).replace("/", ".");
                    val annotationClass = classloader.loadClass(annotationClassName, false, false);
                    val aa = annotationClass.getAnnotations();
                    for (Annotation a : aa) {
                        if (a instanceof HookBy) {
                            ah = true;
                            getInvoker(name, method.name).put(getOrNewRunnable(((HookBy) a).value()));
                        }
                    }
                }
            }

            if (h || mh || ah) {
                newMethods.put(method.name, method);
                method.name += "_IceCreamQAQ_YuHook";
            }
        }

        if (newMethods.size() == 0) return bytes;

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        node.accept(cw);

        val cName = name.replace(".", "/");


        for (String mn : newMethods.keySet()) {
            val method = newMethods.get(mn);
            val a = method.desc.split("\\)");
            val ap = a[0].substring(1);

            int maxPara;

            val paras = readPara(ap);

            maxPara = paras.size();

            val ar = a[1];
            boolean returnFlag = false;
            String returnType = null;
            if (!ar.equals("V")) {
                returnFlag = true;
                returnType = ar;
            }

            val isStatic = (method.access >> 3 & 1) == 1;

            if (!isStatic) maxPara += 1;


            val mv = cw.visitMethod(method.access, mn, method.desc, method.signature, new String[]{"java/lang/Throwable"});

            mv.visitCode();
            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchLabel = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, catchLabel, "java/lang/Throwable");


            int stack = maxPara - 1;
            int hookMethodStack;
            int paraStack;

            {// HookMethod
                val newHookMethodLabel = new Label();
                mv.visitLabel(newHookMethodLabel);
                mv.visitTypeInsn(NEW, "com/IceCreamQAQ/Yu/hook/HookMethod");
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, "com/IceCreamQAQ/Yu/hook/HookMethod", "<init>", "()V", false);
                mv.visitVarInsn(ASTORE, ++stack);

                hookMethodStack = stack;

                val setClassNameLabel = new Label();
                mv.visitLabel(setClassNameLabel);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitLdcInsn(name);
                mv.visitFieldInsn(PUTFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "className", "Ljava/lang/String;");

                val setMethodNameLabel = new Label();
                mv.visitLabel(setMethodNameLabel);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitLdcInsn(mn);
                mv.visitFieldInsn(PUTFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "methodName", "Ljava/lang/String;");

                // Paras
                {
                    val newParasLabel = new Label();
                    mv.visitLabel(newParasLabel);
                    if (isStatic) mv.visitIntInsn(SIPUSH, maxPara + 1);
                    else mv.visitIntInsn(SIPUSH, maxPara);
                    mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

                    mv.visitVarInsn(ASTORE, ++stack);
                    paraStack = stack;

                    if (isStatic) {
                        mv.visitVarInsn(ALOAD, paraStack);
                        mv.visitInsn(ICONST_0);
                        mv.visitInsn(ACONST_NULL);
                        mv.visitInsn(AASTORE);

                        for (int i = 0; i < maxPara; i++) {
                            mv.visitVarInsn(ALOAD, paraStack);
                            mv.visitIntInsn(SIPUSH, i + 1);
                            val p = paras.get(i);
                            if (p.length() == 1) {
                                mv.visitVarInsn(ILOAD, i);
                                val typed = getTyped(p);
                                mv.visitMethodInsn(INVOKESTATIC, typed, "valueOf", "(" + p + ")L" + typed + ";", false);
                            } else {
                                mv.visitVarInsn(ALOAD, i);
                            }
                            mv.visitInsn(AASTORE);

                        }
                    } else {
                        mv.visitVarInsn(ALOAD, paraStack);
                        mv.visitInsn(ICONST_0);
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitInsn(AASTORE);
                        for (int i = 1; i < maxPara; i++) {
                            mv.visitVarInsn(ALOAD, paraStack);
                            mv.visitIntInsn(SIPUSH, i);
                            val p = paras.get(i - 1);
                            if (p.length() == 1) {
                                mv.visitVarInsn(ILOAD, i);
                                val typed = getTyped(p);
                                mv.visitMethodInsn(INVOKESTATIC, typed, "valueOf", "(" + p + ")L" + typed + ";", false);
                            } else {
                                mv.visitVarInsn(ALOAD, i);
                            }
                            mv.visitInsn(AASTORE);
                        }
                    }


                    val setParasLabel = new Label();
                    mv.visitLabel(setParasLabel);
                    mv.visitVarInsn(ALOAD, hookMethodStack);
                    mv.visitVarInsn(ALOAD, paraStack);
                    mv.visitFieldInsn(PUTFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "paras", "[Ljava/lang/Object;");

                }
            }
            int hookRunnableStack;
            {// HookRunnable

                val getHookRunnableLabel = new Label();
                mv.visitLabel(getHookRunnableLabel);

                mv.visitLdcInsn(name);
                mv.visitLdcInsn(mn);
                mv.visitMethodInsn(INVOKESTATIC, "com/IceCreamQAQ/Yu/hook/YuHook", "getInvoker", "(Ljava/lang/String;Ljava/lang/String;)Lcom/IceCreamQAQ/Yu/hook/HookInvokerRunnable;", false);
                mv.visitVarInsn(ASTORE, ++stack);

                hookRunnableStack = stack;
            }
            {// preRun

                val preRunLabel = new Label();
                mv.visitLabel(preRunLabel);
                mv.visitVarInsn(ALOAD, hookRunnableStack);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitMethodInsn(INVOKEINTERFACE, "com/IceCreamQAQ/Yu/hook/HookRunnable", "preRun", "(Lcom/IceCreamQAQ/Yu/hook/HookMethod;)Z", true);
                mv.visitJumpInsn(IFEQ, tryStart);

                if (returnFlag) {
                    mv.visitVarInsn(ALOAD, hookMethodStack);
                    mv.visitFieldInsn(GETFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "result", "Ljava/lang/Object;");
//                    mv.visitTypeInsn(CHECKCAST, returnType);
                    makeCast(mv, returnType);
                    if (returnType.length() == 1) mv.visitInsn(IRETURN);
                    else mv.visitInsn(ARETURN);
                } else {
                    mv.visitInsn(RETURN);
                }
            }
            {// try
                mv.visitLabel(tryStart);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                if (!isStatic) mv.visitVarInsn(ALOAD, 0);

                for (int i = 0; i < paras.size(); i++) {
                    mv.visitVarInsn(ALOAD, paraStack);
                    mv.visitIntInsn(BIPUSH, i + 1);
                    mv.visitInsn(AALOAD);
                    makeCast(mv, paras.get(i));
                }


                if (isStatic) mv.visitMethodInsn(INVOKESTATIC, cName, mn + "_IceCreamQAQ_YuHook", method.desc, false);
                else mv.visitMethodInsn(INVOKEVIRTUAL, cName, mn + "_IceCreamQAQ_YuHook", method.desc, false);
                if (returnFlag) {
                    if (returnType.length() == 1) {
                        val typed = getTyped(returnType);
                        mv.visitMethodInsn(INVOKESTATIC, typed, "valueOf", "(" + returnType + ")L" + typed + ";", false);
                    }
                    mv.visitFieldInsn(PUTFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "result", "Ljava/lang/Object;");
                }

                val postRunLabel = new Label();
                mv.visitLabel(postRunLabel);
                mv.visitVarInsn(ALOAD, hookRunnableStack);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitMethodInsn(INVOKEINTERFACE, "com/IceCreamQAQ/Yu/hook/HookRunnable", "postRun", "(Lcom/IceCreamQAQ/Yu/hook/HookMethod;)V", true);

                val returnLabel = new Label();
                mv.visitLabel(returnLabel);
                mv.visitLabel(tryEnd);
                if (returnFlag) {
                    mv.visitVarInsn(ALOAD, hookMethodStack);
                    mv.visitFieldInsn(GETFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "result", "Ljava/lang/Object;");
                    makeCast(mv, returnType);
                    if (returnType.length() == 1) mv.visitInsn(IRETURN);
                    else mv.visitInsn(ARETURN);
                } else {
                    mv.visitInsn(RETURN);
                }
            }
            {// catch
                mv.visitLabel(catchLabel);
                mv.visitVarInsn(ASTORE, ++stack);

                val errorStack = stack;


                val setErrorLabel = new Label();
                mv.visitLabel(setErrorLabel);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitVarInsn(ALOAD, errorStack);
                mv.visitFieldInsn(PUTFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "error", "Ljava/lang/Throwable;");


                val onErrorLabel = new Label();
                mv.visitLabel(onErrorLabel);
                mv.visitVarInsn(ALOAD, hookRunnableStack);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitMethodInsn(INVOKEINTERFACE, "com/IceCreamQAQ/Yu/hook/HookRunnable", "onError", "(Lcom/IceCreamQAQ/Yu/hook/HookMethod;)Z", true);


                val throwLabel = new Label();
                val returnLabel = new Label();
                mv.visitLabel(returnLabel);
                mv.visitJumpInsn(IFEQ, throwLabel);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitFieldInsn(GETFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "result", "Ljava/lang/Object;");
                if (returnFlag) {
                    mv.visitVarInsn(ALOAD, hookMethodStack);
                    mv.visitFieldInsn(GETFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "result", "Ljava/lang/Object;");
                    makeCast(mv, returnType);
                    if (returnType.length() == 1) mv.visitInsn(IRETURN);
                    else mv.visitInsn(ARETURN);
                } else {
                    mv.visitInsn(RETURN);
                }


                mv.visitLabel(throwLabel);
                mv.visitVarInsn(ALOAD, hookMethodStack);
                mv.visitFieldInsn(GETFIELD, "com/IceCreamQAQ/Yu/hook/HookMethod", "error", "Ljava/lang/Throwable;");
                mv.visitInsn(ATHROW);
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        val bs = cw.toByteArray();

        ClassReader nr = new ClassReader(bs);
        ClassNode nn = new ClassNode();
        nr.accept(nn, 0);

        for (String mn : newMethods.keySet()) {
            val m = ((List<MethodNode>) nn.methods).stream().filter(s -> s.name.equals(mn)).findFirst().get();
            val mh = ((List<MethodNode>) nn.methods).stream().filter(s -> s.name.equals(mn + "_IceCreamQAQ_YuHook")).findFirst().get();

            m.visibleAnnotations = mh.visibleAnnotations;
            mh.visibleAnnotations = null;

            m.invisibleAnnotations = mh.invisibleAnnotations;
            mh.invisibleAnnotations = null;

            m.visibleParameterAnnotations = mh.visibleParameterAnnotations;
            mh.visibleParameterAnnotations = null;
        }

        ClassWriter ncw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        nn.accept(ncw);

        val nbs = ncw.toByteArray();


        return nbs;
    }

    private static String getTyped(String type) {
        switch (type.charAt(0)) {
            case 'B':
                return "java/lang/Byte";
            case 'S':
                return "java/lang/Short";
            case 'I':
                return "java/lang/Integer";
            case 'L':
                return "java/lang/Long";
            case 'F':
                return "java/lang/Float";
            case 'D':
                return "java/lang/Double";
            case 'Z':
                return "java/lang/Boolean";
            case 'C':
                return "java/lang/Character";
        }
        return type;
    }

    private static void makeCast(MethodVisitor mv, String type) {
        if (type.length() == 1) {
            switch (type.charAt(0)) {
                case 'B':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Byte");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                    break;
                case 'S':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Short");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                    break;
                case 'I':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                    break;
                case 'L':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()L", false);
                    break;
                case 'F':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Float");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                    break;
                case 'D':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                    break;
                case 'Z':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                    break;
                case 'C':
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                    break;
            }
        } else {
            if (type.startsWith("L")) type = type.substring(1, type.length() - 1);
            mv.visitTypeInsn(CHECKCAST, type);
        }
    }

    private static List<String> readPara(String desc) {
        val paraList = new ArrayList<String>();
        if (desc.isEmpty()) return paraList;
        val s = desc.toCharArray();

        StringBuilder sb = null;
        for (char c : s) {
            if (sb == null) {
                switch (c) {
                    case '[':
                        sb = new StringBuilder("[");
                        break;
                    case 'L':
                        sb = new StringBuilder();
                        break;
                    default:
                        paraList.add(String.valueOf(c));
                        break;
                }
            } else {
                if (c == ';') {
                    var p = sb.toString();
                    sb = null;
                    if (p.startsWith("[")) p += ";";
                    paraList.add(p);
                } else sb.append(c);
            }
        }
        return paraList;
    }

    static boolean isSysMethod(String name) {
        return name.equals("<init>")
                || name.equals("<clinit>")
                ;
    }
}
