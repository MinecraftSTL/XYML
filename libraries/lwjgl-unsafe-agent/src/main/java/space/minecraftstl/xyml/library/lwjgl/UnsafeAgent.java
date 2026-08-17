/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.lwjgl;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.PrintStream;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

import static java.lang.constant.ConstantDescs.*;

/// Installs the Java 25 transformer that replaces LWJGL `MemoryUtil` accessors with direct Unsafe calls.
@NotNullByDefault
public final class UnsafeAgent {
    /// Prevents construction of the static agent entry-point class.
    private UnsafeAgent() {
    }

    /// Writes an agent diagnostic using its stable prefix.
    ///
    /// @param message diagnostic message
    /// @param output destination stream
    private static void log(String message, PrintStream output) {
        output.println("[lwjgl-unsafe-agent] " + message);
    }

    /// Installs the transformer before the target application's main method starts.
    ///
    /// @param ignoredAgentArguments optional JVM agent argument, currently unused
    /// @param instrumentation JVM instrumentation service
    public static void premain(@Nullable String ignoredAgentArguments, Instrumentation instrumentation) {
        init(instrumentation);
    }

    /// Installs the transformer when the agent is attached to a running JVM.
    ///
    /// @param ignoredAgentArguments optional JVM agent argument, currently unused
    /// @param instrumentation JVM instrumentation service
    public static void agentmain(@Nullable String ignoredAgentArguments, Instrumentation instrumentation) {
        init(instrumentation);
    }

    /// Registers the transformer and reports both the XYML artifact and locked upstream versions.
    ///
    /// @param instrumentation JVM instrumentation service
    private static void init(Instrumentation instrumentation) {
        log("LWJGL Unsafe Agent version: " + BuildConfig.PROJECT_VERSION, System.out);
        log("Upstream version: " + BuildConfig.UPSTREAM_VERSION, System.out);

        instrumentation.addTransformer(new MemoryUtilTransformer(instrumentation));
    }

    /// Rewrites matching static `MemoryUtil` methods as their class is loaded.
    @NotNullByDefault
    private static final class MemoryUtilTransformer implements ClassFileTransformer {
        /// Internal JVM name of the only class this transformer accepts.
        private static final String MEMORY_UTIL_CLASS = "org/lwjgl/system/MemoryUtil";

        /// Descriptor of the Java base Unsafe implementation called by rewritten methods.
        private static final ClassDesc UNSAFE_CLASS_DESCRIPTOR = ClassDesc.of("jdk.internal.misc.Unsafe");

        /// Instrumentation service used to export the internal Unsafe package to the target module.
        private final Instrumentation instrumentation;

        /// Creates a transformer bound to the active instrumentation service.
        ///
        /// @param instrumentation JVM instrumentation service
        MemoryUtilTransformer(Instrumentation instrumentation) {
            this.instrumentation = instrumentation;
        }

        /// Emits the replacement bytecode for one supported MemoryUtil method signature.
        @NotNullByDefault
        private abstract static class MemoryMethodBody implements Consumer<CodeBuilder> {
            /// Descriptor for the static `Unsafe.getUnsafe()` call.
            protected static final MethodTypeDesc GET_UNSAFE_METHOD_TYPE =
                    MethodTypeDesc.of(UNSAFE_CLASS_DESCRIPTOR);

            /// Descriptor the MemoryUtil method must have before it can be rewritten.
            protected final MethodTypeDesc type;

            /// Unsafe method invoked by the replacement body.
            protected final String unsafeMethod;

            /// Creates a replacement body for a specific signature and Unsafe method.
            ///
            /// @param type MemoryUtil method type
            /// @param unsafeMethod Unsafe method name
            private MemoryMethodBody(MethodTypeDesc type, String unsafeMethod) {
                this.type = type;
                this.unsafeMethod = unsafeMethod;
            }

            /// Emits a primitive or address read followed by the matching return instruction.
            @NotNullByDefault
            static final class Get extends MemoryMethodBody {
                /// Primitive return type used by the Unsafe read.
                private final ClassDesc primaryType;

                /// Return-instruction emitter for the primitive type.
                private final Consumer<CodeBuilder> emitReturn;

                /// Creates a primitive read replacement.
                ///
                /// @param primaryType primitive return type
                /// @param unsafeMethod Unsafe read method name
                /// @param emitReturn primitive return-instruction emitter
                private Get(ClassDesc primaryType, String unsafeMethod, Consumer<CodeBuilder> emitReturn) {
                    super(MethodTypeDesc.of(primaryType, CD_long), unsafeMethod);
                    this.primaryType = primaryType;
                    this.emitReturn = emitReturn;
                }

                /// {@inheritDoc}
                @Override
                public void accept(CodeBuilder codeBuilder) {
                    // Push the Unsafe instance
                    codeBuilder.invokestatic(UNSAFE_CLASS_DESCRIPTOR, "getUnsafe", GET_UNSAFE_METHOD_TYPE);

                    // Push the address parameter (slot 0, type long)
                    codeBuilder.lload(0);

                    // Get method: invoke getXxx and return the value
                    codeBuilder.invokevirtual(UNSAFE_CLASS_DESCRIPTOR, unsafeMethod,
                            MethodTypeDesc.of(primaryType, CD_long));
                    emitReturn.accept(codeBuilder);
                }
            }

            /// Emits a primitive or address write followed by a void return.
            @NotNullByDefault
            static final class Put extends MemoryMethodBody {
                /// Primitive value type used by the Unsafe write.
                private final ClassDesc primaryType;

                /// Local-variable load emitter for the primitive value.
                private final ObjIntConsumer<CodeBuilder> loadValue;

                /// Creates a primitive write replacement.
                ///
                /// @param primaryType primitive value type
                /// @param unsafeMethod Unsafe write method name
                /// @param loadValue local-variable load emitter
                private Put(ClassDesc primaryType, String unsafeMethod, ObjIntConsumer<CodeBuilder> loadValue) {
                    super(MethodTypeDesc.of(CD_void, CD_long, primaryType), unsafeMethod);
                    this.primaryType = primaryType;
                    this.loadValue = loadValue;
                }

                /// {@inheritDoc}
                @Override
                public void accept(CodeBuilder codeBuilder) {

                    // Push the Unsafe instance
                    codeBuilder.invokestatic(UNSAFE_CLASS_DESCRIPTOR, "getUnsafe", GET_UNSAFE_METHOD_TYPE);

                    // Push the address parameter (slot 0, type long)
                    codeBuilder.lload(0);

                    // Put method: load value parameter (slot 2) and invoke putXxx
                    loadValue.accept(codeBuilder, 2);
                    codeBuilder.invokevirtual(UNSAFE_CLASS_DESCRIPTOR, unsafeMethod,
                            MethodTypeDesc.of(CD_void, CD_long, primaryType));
                    codeBuilder.return_();
                }
            }
        }

        /// Immutable lookup from supported MemoryUtil method name to its replacement body.
        private final @Unmodifiable Map<String, MemoryMethodBody> bodies = Map.ofEntries(
                // memGetXxx
                Map.entry("memGetByte", new MemoryMethodBody.Get(CD_byte, "getByte", CodeBuilder::ireturn)),
                Map.entry("memGetShort", new MemoryMethodBody.Get(CD_short, "getShort", CodeBuilder::ireturn)),
                Map.entry("memGetInt", new MemoryMethodBody.Get(CD_int, "getInt", CodeBuilder::ireturn)),
                Map.entry("memGetLong", new MemoryMethodBody.Get(CD_long, "getLong", CodeBuilder::lreturn)),
                Map.entry("memGetFloat", new MemoryMethodBody.Get(CD_float, "getFloat", CodeBuilder::freturn)),
                Map.entry("memGetDouble", new MemoryMethodBody.Get(CD_double, "getDouble", CodeBuilder::dreturn)),
                Map.entry("memGetAddress", new MemoryMethodBody.Get(CD_long, "getAddress", CodeBuilder::lreturn)),

                // memPutXxx
                Map.entry("memPutByte", new MemoryMethodBody.Put(CD_byte, "putByte", CodeBuilder::iload)),
                Map.entry("memPutShort", new MemoryMethodBody.Put(CD_short, "putShort", CodeBuilder::iload)),
                Map.entry("memPutInt", new MemoryMethodBody.Put(CD_int, "putInt", CodeBuilder::iload)),
                Map.entry("memPutLong", new MemoryMethodBody.Put(CD_long, "putLong", CodeBuilder::lload)),
                Map.entry("memPutFloat", new MemoryMethodBody.Put(CD_float, "putFloat", CodeBuilder::fload)),
                Map.entry("memPutDouble", new MemoryMethodBody.Put(CD_double, "putDouble", CodeBuilder::dload)),
                Map.entry("memPutAddress", new MemoryMethodBody.Put(CD_long, "putAddress", CodeBuilder::lload))
        );

        /// {@inheritDoc}
        @Override
        public byte @Nullable [] transform(Module module,
                                           @Nullable ClassLoader loader,
                                           @Nullable String className,
                                           @Nullable Class<?> classBeingRedefined,
                                           @Nullable ProtectionDomain protectionDomain,
                                           byte[] classfileBuffer) {
            if (!MEMORY_UTIL_CLASS.equals(className)) {
                return null;
            }

            try {
                Module javaBase = Object.class.getModule();
                String miscPackage = UNSAFE_CLASS_DESCRIPTOR.packageName();
                if (!javaBase.isExported(miscPackage, module)) {
                    instrumentation.redefineModule(javaBase,
                            Set.of(),
                            Map.of(miscPackage, Set.of(module)),
                            Map.of(),
                            Set.of(),
                            Map.of()
                    );

                    String targetModuleName;
                    if (module.isNamed()) {
                        targetModuleName = module.getName();
                    } else {
                        targetModuleName = "<unnamed module for " + loader + ">";
                    }

                    log("Add exports %s/%s to %s".formatted(javaBase.getName(), miscPackage, targetModuleName), System.out);
                }
            } catch (Exception e) {
                log("Failed to redefine module", System.err);
                e.printStackTrace(System.err);
                return null;
            }

            try {
                var classFile = ClassFile.of();
                byte[] result = classFile.transformClass(classFile.parse(classfileBuffer), this::transform);
                log("Successfully transformed MemoryUtil", System.out);
                return result;
            } catch (Exception e) {
                log("Failed to transform MemoryUtil", System.err);
                e.printStackTrace(System.err);
                return null;
            }
        }

        /// Copies a class element or replaces a recognized method body.
        ///
        /// @param classBuilder destination class builder
        /// @param classElement source class element
        private void transform(ClassBuilder classBuilder, ClassElement classElement) {
            if (classElement instanceof MethodModel methodModel) {
                String methodName = methodModel.methodName().stringValue();
                AccessFlags methodFlags = methodModel.flags();

                MemoryMethodBody body = bodies.get(methodName);
                if (methodFlags.has(AccessFlag.STATIC)
                        && body != null
                        && body.type.descriptorString().equals(methodModel.methodType().stringValue())) {
                    classBuilder.withMethod(methodName, body.type, methodFlags.flagsMask(), mb -> {
                        for (MethodElement me : methodModel) {
                            if (me instanceof CodeModel) {
                                // Replace the method body with a direct call to `jdk.internal.misc.Unsafe`.
                                mb.withCode(body);
                            } else {
                                // Preserve non-code method attributes (annotations, etc.)
                                mb.with(me);
                            }
                        }
                    });

                    log("Rewrote %s.%s(%s)".formatted(
                            MEMORY_UTIL_CLASS.replace('/', '.'),
                            methodName,
                            body.type.parameterList().stream()
                                    .map(ClassDesc::displayName)
                                    .collect(Collectors.joining(", "))
                            ), System.out);
                    return;
                }
            }
            classBuilder.with(classElement);
        }
    }

}
