package com.llmops.rag.config;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

public class RedisCommandExecutor {

    private static Object unwrapConnection(Object conn) {
        if (conn == null) return null;
        if (!Proxy.isProxyClass(conn.getClass())) {
            return conn;
        }
        try {
            java.lang.reflect.InvocationHandler handler = Proxy.getInvocationHandler(conn);
            for (Field field : handler.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object val = field.get(handler);
                if (val != null) {
                    if (org.springframework.data.redis.connection.ReactiveRedisConnection.class.isAssignableFrom(val.getClass())) {
                        return unwrapConnection(val);
                    }
                    if ("target".equals(field.getName()) || "delegate".equals(field.getName()) || "connection".equals(field.getName())) {
                        return unwrapConnection(val);
                    }
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return conn;
    }

    public static Flux<Object> execute(ReactiveRedisTemplate<String, String> redisTemplate, String commandName, byte[]... args) {
        return redisTemplate.execute(conn -> {
            try {
                Object unwrapped = unwrapConnection(conn);
                Method getConnectionMethod = null;
                try {
                    getConnectionMethod = unwrapped.getClass().getMethod("getConnection");
                } catch (NoSuchMethodException e) {
                    getConnectionMethod = unwrapped.getClass().getDeclaredMethod("getConnection");
                }
                getConnectionMethod.setAccessible(true);
                Mono<?> connectionMono = (Mono<?>) getConnectionMethod.invoke(unwrapped);

                return connectionMono.flatMapMany(statefulConn -> {
                    try {
                        // call statefulConn.reactive() to get RedisClusterReactiveCommands
                        Method reactiveMethod = statefulConn.getClass().getMethod("reactive");
                        Object reactiveCommands = reactiveMethod.invoke(statefulConn);

                        // Call dispatch(ProtocolKeyword, CommandOutput, CommandArgs)
                        Method dispatchMethod = reactiveCommands.getClass().getMethod(
                                "dispatch",
                                ProtocolKeyword.class,
                                io.lettuce.core.output.CommandOutput.class,
                                CommandArgs.class
                        );

                        // Define a ProtocolKeyword for our command
                        ProtocolKeyword keyword = new ProtocolKeyword() {
                            @Override
                            public byte[] getBytes() {
                                return commandName.getBytes(StandardCharsets.UTF_8);
                            }

                            @Override
                            public String name() {
                                return commandName;
                            }
                        };

                        // Build CommandArgs
                        ByteArrayCodec codec = ByteArrayCodec.INSTANCE;
                        CommandArgs<byte[], byte[]> commandArgs = new CommandArgs<>(codec);
                        for (byte[] arg : args) {
                            commandArgs.add(arg);
                        }

                        // Call dispatch
                        @SuppressWarnings("unchecked")
                        Flux<Object> resultFlux = (Flux<Object>) dispatchMethod.invoke(
                                reactiveCommands,
                                keyword,
                                new NestedMultiOutput<>(codec),
                                commandArgs
                        );
                        return resultFlux;
                    } catch (Exception e) {
                        return Flux.error(e);
                    }
                });
            } catch (Exception e) {
                return Mono.error(e);
            }
        });
    }
}
