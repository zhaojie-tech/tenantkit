/*
 * Copyright 2026 TenantKit Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tenantkit.cache.redis;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.StreamOffset;

/**
 * Key-rewriting proxies for every Redis ops family (v0.1 now covers all
 * key-based operations, not just value ops). Each proxy delegates to the same
 * method on the native ops object with key-shaped arguments encoded and, for
 * stream reads, stream keys in returned records decoded back.
 *
 * <p>The rewriting is <em>declarative per family</em>, never argument-value
 * guesswork: every ops interface takes its primary key as the first String
 * argument; the handful of multi-key methods are named explicitly below
 * ({@code SetOperations.move}, the {@code *AndStore} variants,
 * {@code ListOperations.rightPopAndLeftPush},
 * {@code HyperLogLogOperations.union}, stream {@code read}/{@code range}/
 * {@code add}/{@code delete}/{@code acknowledge} with records). Values and
 * fields are never touched, so a String <em>value</em> can never be mistaken
 * for a key.
 */
final class TenantOpsProxies {

    private static final String VALUE = "ValueOperations";
    private static final String SET = "SetOperations";
    private static final String ZSET = "ZSetOperations";
    private static final String LIST = "ListOperations";
    private static final String HLL = "HyperLogLogOperations";
    private static final String STREAM = "StreamOperations";

    private TenantOpsProxies() {
    }

    @SuppressWarnings("unchecked")
    static <T> T wrap(Class<T> opsInterface, T target, Function<String, String> encoder,
            Function<String, String> decoder) {
        return wrap(opsInterface, target, encoder, decoder, null);
    }

    /** With {@code getOperationsEscape}: the value {@code getOperations()} must return. */
    @SuppressWarnings("unchecked")
    static <T> T wrap(Class<T> opsInterface, T target, Function<String, String> encoder,
            Function<String, String> decoder, Object getOperationsEscape) {
        InvocationHandler handler = new Handler(opsInterface, target, encoder, decoder, getOperationsEscape);
        return (T) Proxy.newProxyInstance(TenantOpsProxies.class.getClassLoader(),
                new Class<?>[] { opsInterface }, handler);
    }

    private static final class Handler implements InvocationHandler {

        private final Class<?> opsInterface;
        private final Object target;
        private final Function<String, String> encoder;
        private final Function<String, String> decoder;
        private final String family;
        private final Object getOperationsEscape;

        Handler(Class<?> opsInterface, Object target, Function<String, String> encoder,
                Function<String, String> decoder, Object getOperationsEscape) {
            this.opsInterface = opsInterface;
            this.target = target;
            this.encoder = encoder;
            this.decoder = decoder;
            this.family = opsInterface.getSimpleName();
            this.getOperationsEscape = getOperationsEscape;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }
            if ("getOperations".equals(method.getName()) && getOperationsEscape != null) {
                return getOperationsEscape;
            }
            Object[] rewritten = args == null ? new Object[0] : args.clone();
            rewriteArguments(method.getName(), rewritten);
            Object result;
            try {
                result = method.invoke(target, rewritten);
            }
            catch (InvocationTargetException ex) {
                throw ex.getCause() == null ? ex : ex.getCause();
            }
            return decodeResult(method.getName(), result);
        }

        private void rewriteArguments(String method, Object[] args) {
            if (args.length == 0) {
                return;
            }
            if (VALUE.equals(family)) {
                rewriteValueArguments(method, args);
                return;
            }
            if (STREAM.equals(family)) {
                rewriteStreamArguments(method, args);
                return;
            }
            if (SET.equals(family)) {
                if (method.equals("move")) {
                    encodeAt(args, 0);
                    encodeAt(args, 2);
                    return;
                }
                if (method.equals("difference") || method.equals("intersect") || method.equals("union")) {
                    encodeKeyOrCollection(args, 0);
                    encodeKeyOrCollection(args, 1);
                    return;
                }
                if (method.endsWith("AndStore")) {
                    encodeKeyOrCollection(args, 0);
                    encodeKeyOrCollection(args, 1);
                    encodeKeyOrCollection(args, 2);
                    return;
                }
                encodeAt(args, 0);
                return;
            }
            if (ZSET.equals(family)) {
                if (method.endsWith("AndStore")) {
                    encodeKeyOrCollection(args, 0);
                    encodeKeyOrCollection(args, 1);
                    encodeKeyOrCollection(args, 2);
                    return;
                }
                encodeAt(args, 0);
                return;
            }
            if (LIST.equals(family)) {
                encodeAt(args, 0);
                if (method.equals("rightPopAndLeftPush")) {
                    encodeAt(args, 1);
                }
                return;
            }
            if (HLL.equals(family)) {
                encodeAt(args, 0);
                if (method.equals("union")) {
                    encodeKeyOrCollection(args, 1);
                }
                return;
            }
            // HashOperations and GeoOperations: primary key first, values/fields untouched
            encodeAt(args, 0);
        }

        /** ValueOperations: key first everywhere; the bulk methods carry a Map of keys. */
        private void rewriteValueArguments(String method, Object[] args) {
            if ("multiGet".equals(method) && args[0] instanceof Collection<?> keys) {
                java.util.List<Object> encoded = new java.util.ArrayList<>(keys.size());
                for (Object key : keys) {
                    encoded.add(key instanceof String text ? encoder.apply(text) : key);
                }
                args[0] = encoded;
                return;
            }
            if (("multiSet".equals(method) || "multiSetIfAbsent".equals(method))
                    && args[0] instanceof java.util.Map<?, ?> map) {
                java.util.LinkedHashMap<Object, Object> encoded = new java.util.LinkedHashMap<>(map.size());
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    encoded.put(key instanceof String text ? encoder.apply(text) : key, entry.getValue());
                }
                args[0] = encoded;
                return;
            }
            encodeAt(args, 0);
        }

        private void rewriteStreamArguments(String method, Object[] args) {
            switch (method) {
                case "read", "readAll" -> {
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof StreamOffset<?>[] offsets) {
                            StreamOffset<?>[] rebuilt = new StreamOffset<?>[offsets.length];
                            for (int j = 0; j < offsets.length; j++) {
                                rebuilt[j] = StreamOffset.create(
                                        encoder.apply(offsets[j].getKey().toString()),
                                        offsets[j].getOffset());
                            }
                            args[i] = rebuilt;
                        }
                        else if (args[i] instanceof Consumer[] consumers) {
                            // consumers carry group+name only; stream keys live in the offsets
                            continue;
                        }
                    }
                }
                case "range", "reverseRange" -> {
                    if (args[0] instanceof String) {
                        encodeAt(args, 0);
                    }
                    else {
                        encodeAt(args, 1);
                    }
                }
                case "add", "delete" -> {
                    if (args.length == 1 && args[0] instanceof Record<?, ?> record) {
                        args[0] = withEncodedStreamKey(record);
                    }
                    else {
                        encodeAt(args, 0);
                    }
                }
                case "acknowledge" -> {
                    if (args.length == 2 && args[1] instanceof Record<?, ?> record) {
                        args[1] = withEncodedStreamKey(record);
                    }
                    else {
                        encodeAt(args, 0);
                    }
                }
                default -> encodeAt(args, 0);
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private Object withEncodedStreamKey(Record<?, ?> record) {
            String encoded = encoder.apply(record.getStream().toString());
            if (record instanceof MapRecord<?, ?, ?> mapRecord) {
                return mapRecord.withStreamKey(encoded);
            }
            if (record instanceof ObjectRecord<?, ?> objectRecord) {
                return objectRecord.withStreamKey(encoded);
            }
            if (record instanceof ByteRecord byteRecord) {
                MapRecord<byte[], byte[], byte[]> rebuilt = (MapRecord) MapRecord.create(
                        encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        byteRecord.getValue()).withId(byteRecord.getId());
                return ByteRecord.of(rebuilt);
            }
            throw new UnsupportedOperationException(
                    "stream record type " + record.getClass().getName()
                            + " cannot be re-keyed; use the add/delete overload that takes the stream key explicitly");
        }

        private Object decodeResult(String method, Object result) {
            if (!STREAM.equals(family) || !(result instanceof List<?> records) || records.isEmpty()) {
                return result;
            }
            if (!"range".equals(method) && !"reverseRange".equals(method) && !"read".equals(method)) {
                return result;
            }
            List<Object> decoded = new ArrayList<>(records.size());
            for (Object candidate : records) {
                if (candidate instanceof Record<?, ?> record) {
                    String logical = decoder.apply(record.getStream().toString());
                    decoded.add(rewriteStreamKey(record, logical));
                }
                else {
                    decoded.add(candidate);
                }
            }
            return decoded;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private Object rewriteStreamKey(Record<?, ?> record, String logicalKey) {
            if (record instanceof MapRecord<?, ?, ?> mapRecord) {
                return mapRecord.withStreamKey(logicalKey);
            }
            if (record instanceof ObjectRecord<?, ?> objectRecord) {
                return objectRecord.withStreamKey(logicalKey);
            }
            if (record instanceof ByteRecord byteRecord) {
                MapRecord<byte[], byte[], byte[]> rebuilt = (MapRecord) MapRecord.create(
                        logicalKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        byteRecord.getValue()).withId(byteRecord.getId());
                return ByteRecord.of(rebuilt);
            }
            return record;
        }

        private void encodeAt(Object[] args, int index) {
            if (index < args.length && args[index] instanceof String key) {
                args[index] = encoder.apply(key);
            }
        }

        /** Key argument that may be a single key, a collection of keys, or a varargs array. */
        private void encodeKeyOrCollection(Object[] args, int index) {
            if (index >= args.length) {
                return;
            }
            Object arg = args[index];
            if (arg instanceof String key) {
                args[index] = encoder.apply(key);
            }
            else if (arg instanceof Collection<?> collection) {
                List<Object> encoded = new ArrayList<>(collection.size());
                for (Object element : collection) {
                    encoded.add(element instanceof String key ? encoder.apply(key) : element);
                }
                args[index] = encoded;
            }
            else if (arg instanceof Object[] array) {
                Object[] encoded = new Object[array.length];
                for (int i = 0; i < array.length; i++) {
                    encoded[i] = array[i] instanceof String key ? encoder.apply(key) : array[i];
                }
                args[index] = encoded;
            }
        }
    }
}
