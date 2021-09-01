/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.code;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

// Checkstyle: allow reflection

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.graalvm.compiler.core.common.util.TypeConversion;
import org.graalvm.compiler.core.common.util.UnsafeArrayTypeWriter;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.util.GuardedAnnotationAccess;

import com.oracle.svm.core.c.NonmovableArray;
import com.oracle.svm.core.c.NonmovableArrays;
import com.oracle.svm.core.hub.DynamicHubSupport;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.meta.SharedType;
import com.oracle.svm.core.util.ByteArrayReader;
import com.oracle.svm.util.ReflectionUtil;

import sun.invoke.util.Wrapper;
import sun.reflect.annotation.AnnotationType;

@Platforms(Platform.HOSTED_ONLY.class)
public class MethodMetadataEncoder {
    public static final int NO_METHOD_METADATA = -1;

    private CodeInfoEncoder.Encoders encoders;
    private TreeMap<SharedType, Set<Executable>> methodData;

    private NonmovableArray<Byte> methodDataEncoding;
    private NonmovableArray<Byte> methodDataIndexEncoding;

    MethodMetadataEncoder(CodeInfoEncoder.Encoders encoders) {
        this.encoders = encoders;
        this.methodData = new TreeMap<>(Comparator.comparingLong(t -> t.getHub().getTypeID()));
    }

    void encodeAllAndInstall(CodeInfo target) {
        encodeMethodMetadata();
        CodeInfoAccess.setMethodMetadata(target, methodDataEncoding, methodDataIndexEncoding);
    }

    @SuppressWarnings("unchecked")
    public void prepareMetadataForClass(Class<?> clazz) {
        encoders.sourceClasses.addObject(clazz);
        if (clazz.isAnnotation()) {
            try {
                for (String valueName : AnnotationType.getInstance((Class<? extends Annotation>) clazz).members().keySet()) {
                    encoders.sourceMethodNames.addObject(valueName);
                }
            } catch (LinkageError | RuntimeException t) {
                // ignore
            }
        }
    }

    public void prepareMetadataForMethod(SharedMethod method, Executable reflectMethod) {
        if (reflectMethod instanceof Constructor<?>) {
            encoders.sourceMethodNames.addObject("<init>");
        } else {
            encoders.sourceMethodNames.addObject(reflectMethod.getName());
        }
        encoders.sourceMethodNames.addObject(getSignature(reflectMethod));

        /* Register string values in annotations */
        registerStrings(GuardedAnnotationAccess.getDeclaredAnnotations(reflectMethod));
        for (Annotation[] annotations : reflectMethod.getParameterAnnotations()) {
            registerStrings(annotations);
        }
        SharedType declaringType = (SharedType) method.getDeclaringClass();
        methodData.computeIfAbsent(declaringType, t -> new HashSet<>()).add(reflectMethod);
    }

    private void encodeMethodMetadata() {
        UnsafeArrayTypeWriter dataEncodingBuffer = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        UnsafeArrayTypeWriter indexEncodingBuffer = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        long lastTypeID = -1;
        for (Map.Entry<SharedType, Set<Executable>> entry : methodData.entrySet()) {
            SharedType declaringType = entry.getKey();
            Set<Executable> methods = entry.getValue();
            long typeID = declaringType.getHub().getTypeID();
            assert typeID > lastTypeID;
            lastTypeID++;
            while (lastTypeID < typeID) {
                indexEncodingBuffer.putS4(NO_METHOD_METADATA);
                lastTypeID++;
            }
            long index = dataEncodingBuffer.getBytesWritten();
            indexEncodingBuffer.putS4(index);
            dataEncodingBuffer.putUV(methods.size());
            for (Executable method : methods) {
                String name = method instanceof Constructor<?> ? "<init>" : ((Method) method).getName();
                final int nameIndex = encoders.sourceMethodNames.getIndex(name);
                dataEncodingBuffer.putSV(nameIndex);

                dataEncodingBuffer.putUV(method.getModifiers());

                Class<?>[] parameterTypes = method.getParameterTypes();
                dataEncodingBuffer.putUV(parameterTypes.length);
                for (Class<?> parameterType : parameterTypes) {
                    final int paramClassIndex = encoders.sourceClasses.getIndex(encoders.sourceClasses.contains(parameterType) ? parameterType : Object.class);
                    dataEncodingBuffer.putSV(paramClassIndex);
                }

                Class<?> returnType = method instanceof Constructor<?> ? void.class : ((Method) method).getReturnType();
                final int returnTypeIndex = encoders.sourceClasses.getIndex(encoders.sourceClasses.contains(returnType) ? returnType : Object.class);
                dataEncodingBuffer.putSV(returnTypeIndex);

                /* Only include types that are in the image (i.e. that can actually be thrown) */
                Class<?>[] exceptionTypes = filterTypes(method.getExceptionTypes());
                dataEncodingBuffer.putUV(exceptionTypes.length);
                for (Class<?> exceptionClazz : exceptionTypes) {
                    final int exceptionClassIndex = encoders.sourceClasses.getIndex(exceptionClazz);
                    dataEncodingBuffer.putSV(exceptionClassIndex);
                }

                final int signatureIndex = encoders.sourceMethodNames.getIndex(getSignature(method));
                dataEncodingBuffer.putSV(signatureIndex);

                try {
                    byte[] annotations = encodeAnnotations(GuardedAnnotationAccess.getDeclaredAnnotations(method));
                    dataEncodingBuffer.putUV(annotations.length);
                    for (byte b : annotations) {
                        dataEncodingBuffer.putS1(b);
                    }
                    byte[] parameterAnnotations = encodeParameterAnnotations(method.getParameterAnnotations());
                    dataEncodingBuffer.putUV(parameterAnnotations.length);
                    for (byte b : parameterAnnotations) {
                        dataEncodingBuffer.putS1(b);
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw shouldNotReachHere();
                }
            }
        }
        while (lastTypeID < ImageSingletons.lookup(DynamicHubSupport.class).getMaxTypeId()) {
            indexEncodingBuffer.putS4(NO_METHOD_METADATA);
            lastTypeID++;
        }
        methodDataEncoding = NonmovableArrays.createByteArray(TypeConversion.asS4(dataEncodingBuffer.getBytesWritten()));
        dataEncodingBuffer.toByteBuffer(NonmovableArrays.asByteBuffer(methodDataEncoding));
        methodDataIndexEncoding = NonmovableArrays.createByteArray(TypeConversion.asS4(indexEncodingBuffer.getBytesWritten()));
        indexEncodingBuffer.toByteBuffer(NonmovableArrays.asByteBuffer(methodDataIndexEncoding));
    }

    private Class<?>[] filterTypes(Class<?>[] types) {
        List<Class<?>> filteredTypes = new ArrayList<>();
        for (Class<?> type : types) {
            if (encoders.sourceClasses.contains(type)) {
                filteredTypes.add(type);
            }
        }
        return filteredTypes.toArray(new Class<?>[0]);
    }

    private static final Method getMethodSignature = ReflectionUtil.lookupMethod(Method.class, "getGenericSignature");
    private static final Method getConstructorSignature = ReflectionUtil.lookupMethod(Constructor.class, "getSignature");

    private static String getSignature(Executable method) {
        try {
            return (String) (method instanceof Method ? getMethodSignature.invoke(method) : getConstructorSignature.invoke(method));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw shouldNotReachHere();
        }
    }

    /**
     * The following methods encode annotations attached to a method or parameter in a format based
     * on the one used internally by the JDK ({@link sun.reflect.annotation.AnnotationParser}). The
     * format we use differs from that one on a few points, based on the fact that the JDK encoding
     * is based on constant pool indices, which are not available in that form at runtime.
     *
     * Class and String values are represented by their index in the source metadata encoders
     * instead of their constant pool indices. Additionally, Class objects are encoded directly
     * instead of through their type signature. Primitive values are written directly into the
     * encoding. This means that our encoding can be of a different length from the JDK one.
     *
     * We use a modified version of the ConstantPool and AnnotationParser classes to decode the
     * data, since those are not used in their original functions at runtime. (see
     * {@link com.oracle.svm.core.jdk.Target_jdk_internal_reflect_ConstantPool})
     */
    byte[] encodeAnnotations(Annotation[] annotations) throws InvocationTargetException, IllegalAccessException {
        UnsafeArrayTypeWriter buf = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());

        Annotation[] filteredAnnotations = filterAnnotations(annotations);
        buf.putU2(filteredAnnotations.length);
        for (Annotation annotation : filteredAnnotations) {
            encodeAnnotation(buf, annotation);
        }

        return buf.toArray();
    }

    byte[] encodeParameterAnnotations(Annotation[][] annotations) throws InvocationTargetException, IllegalAccessException {
        UnsafeArrayTypeWriter buf = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());

        buf.putU1(annotations.length);
        for (Annotation[] parameterAnnotations : annotations) {
            Annotation[] filteredParameterAnnotations = filterAnnotations(parameterAnnotations);
            buf.putU2(filteredParameterAnnotations.length);
            for (Annotation parameterAnnotation : filteredParameterAnnotations) {
                encodeAnnotation(buf, parameterAnnotation);
            }
        }

        return buf.toArray();
    }

    void encodeAnnotation(UnsafeArrayTypeWriter buf, Annotation annotation) throws InvocationTargetException, IllegalAccessException {
        buf.putS4(encoders.sourceClasses.getIndex(annotation.annotationType()));
        AnnotationType type = AnnotationType.getInstance(annotation.annotationType());
        buf.putU2(type.members().size());
        for (Map.Entry<String, Method> entry : type.members().entrySet()) {
            String memberName = entry.getKey();
            Method valueAccessor = entry.getValue();
            buf.putS4(encoders.sourceMethodNames.getIndex(memberName));
            encodeValue(buf, valueAccessor.invoke(annotation), type.memberTypes().get(memberName));
        }
    }

    void encodeValue(UnsafeArrayTypeWriter buf, Object value, Class<?> type) throws InvocationTargetException, IllegalAccessException {
        buf.putU1(tag(type));
        if (type.isAnnotation()) {
            encodeAnnotation(buf, (Annotation) value);
        } else if (type.isEnum()) {
            buf.putS4(encoders.sourceClasses.getIndex(type));
            buf.putS4(encoders.sourceMethodNames.getIndex(((Enum<?>) value).name()));
        } else if (type.isArray()) {
            encodeArray(buf, value, type.getComponentType());
        } else if (type == Class.class) {
            buf.putS4(encoders.sourceClasses.getIndex((Class<?>) value));
        } else if (type == String.class) {
            buf.putS4(encoders.sourceMethodNames.getIndex((String) value));
        } else if (type.isPrimitive() || Wrapper.isWrapperType(type)) {
            Wrapper wrapper = type.isPrimitive() ? Wrapper.forPrimitiveType(type) : Wrapper.forWrapperType(type);
            switch (wrapper) {
                case BOOLEAN:
                    buf.putU1((boolean) value ? 1 : 0);
                    break;
                case BYTE:
                    buf.putS1((byte) value);
                    break;
                case SHORT:
                    buf.putS2((short) value);
                    break;
                case CHAR:
                    buf.putU2((char) value);
                    break;
                case INT:
                    buf.putS4((int) value);
                    break;
                case LONG:
                    buf.putS8((long) value);
                    break;
                case FLOAT:
                    buf.putS4(Float.floatToRawIntBits((float) value));
                    break;
                case DOUBLE:
                    buf.putS8(Double.doubleToRawLongBits((double) value));
                    break;
                default:
                    throw shouldNotReachHere();
            }
        } else {
            throw shouldNotReachHere();
        }
    }

    void encodeArray(UnsafeArrayTypeWriter buf, Object value, Class<?> componentType) throws InvocationTargetException, IllegalAccessException {
        if (!componentType.isPrimitive()) {
            Object[] array = (Object[]) value;
            buf.putU2(array.length);
            for (Object val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == boolean.class) {
            boolean[] array = (boolean[]) value;
            buf.putU2(array.length);
            for (boolean val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == byte.class) {
            byte[] array = (byte[]) value;
            buf.putU2(array.length);
            for (byte val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == short.class) {
            short[] array = (short[]) value;
            buf.putU2(array.length);
            for (short val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == char.class) {
            char[] array = (char[]) value;
            buf.putU2(array.length);
            for (char val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == int.class) {
            int[] array = (int[]) value;
            buf.putU2(array.length);
            for (int val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == long.class) {
            long[] array = (long[]) value;
            buf.putU2(array.length);
            for (long val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == float.class) {
            float[] array = (float[]) value;
            buf.putU2(array.length);
            for (float val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == double.class) {
            double[] array = (double[]) value;
            buf.putU2(array.length);
            for (double val : array) {
                encodeValue(buf, val, componentType);
            }
        }
    }

    byte tag(Class<?> type) {
        if (type.isAnnotation()) {
            return '@';
        } else if (type.isEnum()) {
            return 'e';
        } else if (type.isArray()) {
            return '[';
        } else if (type == Class.class) {
            return 'c';
        } else if (type == String.class) {
            return 's';
        } else if (type.isPrimitive()) {
            return (byte) Wrapper.forPrimitiveType(type).basicTypeChar();
        } else if (Wrapper.isWrapperType(type)) {
            return (byte) Wrapper.forWrapperType(type).basicTypeChar();
        } else {
            throw shouldNotReachHere();
        }
    }

    private Annotation[] filterAnnotations(Annotation[] annotations) {
        List<Annotation> filteredAnnotations = new ArrayList<>();
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> annotationClass = annotation.annotationType();
            if (supportedValue(annotationClass, annotation, null)) {
                filteredAnnotations.add(annotation);
            }
        }
        return filteredAnnotations.toArray(new Annotation[0]);
    }

    private void registerStrings(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            List<String> stringValues = new ArrayList<>();
            if (supportedValue(annotation.annotationType(), annotation, stringValues)) {
                for (String stringValue : stringValues) {
                    encoders.sourceMethodNames.addObject(stringValue);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean supportedValue(Class<?> type, Object value, List<String> stringValues) {
        if (type.isAnnotation()) {
            Annotation annotation = (Annotation) value;
            if (!encoders.sourceClasses.contains(annotation.annotationType())) {
                return false;
            }
            AnnotationType annotationType = AnnotationType.getInstance((Class<? extends Annotation>) type);
            for (Map.Entry<String, Class<?>> entry : annotationType.memberTypes().entrySet()) {
                String valueName = entry.getKey();
                Class<?> valueType = entry.getValue();
                try {
                    Method getAnnotationValue = annotationType.members().get(valueName);
                    getAnnotationValue.setAccessible(true);
                    Object annotationValue = getAnnotationValue.invoke(annotation);
                    if (!supportedValue(valueType, annotationValue, stringValues)) {
                        return false;
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    return false;
                }
            }
        } else if (type.isArray()) {
            boolean supported = true;
            Class<?> componentType = type.getComponentType();
            if (!componentType.isPrimitive()) {
                for (Object val : (Object[]) value) {
                    supported &= supportedValue(componentType, val, stringValues);
                }
            }
            return supported;
        } else if (type == Class.class) {
            return encoders.sourceClasses.contains((Class<?>) value);
        } else if (type == String.class) {
            if (stringValues != null) {
                stringValues.add((String) value);
            }
        } else if (type.isEnum()) {
            if (stringValues != null) {
                stringValues.add(((Enum<?>) value).name());
            }
            return encoders.sourceClasses.contains(type);
        }
        return true;
    }
}
