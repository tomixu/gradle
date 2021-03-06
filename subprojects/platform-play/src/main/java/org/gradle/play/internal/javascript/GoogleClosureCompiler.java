/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.play.internal.javascript;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.internal.file.RelativeFile;
import org.gradle.api.internal.tasks.SimpleWorkResult;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.reflect.DirectInstantiator;
import org.gradle.internal.reflect.JavaMethod;
import org.gradle.internal.reflect.JavaReflectionUtil;
import org.gradle.internal.reflect.PropertyAccessor;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.plugins.javascript.base.SourceTransformationException;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("rawtypes")

public class GoogleClosureCompiler implements Compiler<JavaScriptCompileSpec>, Serializable {
    private static final String DEFAULT_GOOGLE_CLOSURE_VERSION = "v20141215";
    private Class sourceFileClass;
    private Class compilerOptionsClass;
    private Class compilationLevelClass;
    private Class compilerClass;

    public List<String> getClassLoaderPackages() {
        return Lists.newArrayList("com.google.javascript");
    }

    public Object getDependencyNotation() {
        return String.format("com.google.javascript:closure-compiler:%s", DEFAULT_GOOGLE_CLOSURE_VERSION);
    }

    @Override
    public WorkResult execute(JavaScriptCompileSpec spec) {
        JavaScriptCompileDestinationCalculator destinationCalculator = new JavaScriptCompileDestinationCalculator(spec.getDestinationDir());
        List<String> allErrors = Lists.newArrayList();

        for (RelativeFile sourceFile : spec.getSources()) {
            allErrors.addAll(compile(sourceFile, spec, destinationCalculator));
        }

        if (allErrors.isEmpty()) {
            return new SimpleWorkResult(true);
        } else {
            throw new SourceTransformationException(String.format("Minification failed with the following errors:\n\t%s", StringUtils.join(allErrors, "\n\t")), null);
        }
    }

    @SuppressWarnings("unchecked")
    List<String> compile(RelativeFile javascriptFile, JavaScriptCompileSpec spec, JavaScriptCompileDestinationCalculator destinationCalculator) {
        List<String> errors = Lists.newArrayList();

        loadCompilerClasses(getClass().getClassLoader());

        // Create a SourceFile object to represent an "empty" extern
        JavaMethod fromCodeJavaMethod = getStaticMethod(sourceFileClass, Object.class, "fromCode", String.class, String.class);
        Object extern = fromCodeJavaMethod.invoke(null, "/dev/null", "");

        // Create a SourceFile object to represent the javascript file to compile
        JavaMethod fromFileJavaMethod = getStaticMethod(sourceFileClass, Object.class, "fromFile", File.class);
        Object sourceFile = fromFileJavaMethod.invoke(null, javascriptFile.getFile());

        // Construct a new CompilerOptions class
        Factory<?> compilerOptionsFactory = JavaReflectionUtil.factory(new DirectInstantiator(), compilerOptionsClass);
        Object compilerOptions = compilerOptionsFactory.create();

        // Get the CompilationLevel.SIMPLE_OPTIMIZATIONS class and set it on the CompilerOptions class
        Enum simpleLevel = Enum.valueOf(compilationLevelClass, "SIMPLE_OPTIMIZATIONS");
        JavaMethod setOptionsForCompilationLevelMethod = JavaReflectionUtil.method(compilationLevelClass, Void.class, "setOptionsForCompilationLevel", compilerOptionsClass);
        setOptionsForCompilationLevelMethod.invoke(simpleLevel, compilerOptions);

        // Construct a new Compiler class
        Factory<?> compilerFactory = JavaReflectionUtil.factory(new DirectInstantiator(), compilerClass, getDummyPrintStream());
        Object compiler = compilerFactory.create();

        // Compile the javascript file with the options we've created
        JavaMethod compileMethod = JavaReflectionUtil.method(compilerClass, Object.class, "compile", sourceFileClass, sourceFileClass, compilerOptionsClass);
        Object result = compileMethod.invoke(compiler, extern, sourceFile, compilerOptions);

        // Get any errors from the compiler result
        PropertyAccessor jsErrorsField = JavaReflectionUtil.readableField(result.getClass(), "errors");
        Object[] jsErrors = (Object[]) jsErrorsField.getValue(result);

        if (jsErrors.length == 0) {
            // If no errors, get the compiled source and write it to the destination file
            JavaMethod<Object, String> toSourceMethod = JavaReflectionUtil.method(compilerClass, String.class, "toSource");
            String compiledSource = toSourceMethod.invoke(compiler);
            GFileUtils.writeFile(compiledSource, destinationCalculator.transform(javascriptFile));
        } else {
            for (Object error : jsErrors) {
                errors.add(error.toString());
            }
        }

        return errors;
    }

    private void loadCompilerClasses(ClassLoader cl) {
        try {
            if (sourceFileClass == null) {
                sourceFileClass = cl.loadClass("com.google.javascript.jscomp.SourceFile");
            }
            if (compilerOptionsClass == null) {
                compilerOptionsClass = cl.loadClass("com.google.javascript.jscomp.CompilerOptions");
            }
            if (compilationLevelClass == null) {
                compilationLevelClass = cl.loadClass("com.google.javascript.jscomp.CompilationLevel");
            }
            if (compilerClass == null) {
                compilerClass = cl.loadClass("com.google.javascript.jscomp.Compiler");
            }
        } catch (ClassNotFoundException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    /**
     * We have to find static methods like this because the other JavaReflectionUtil.method() ignores static methods
     */
    private static <T, R> JavaMethod<T, R> getStaticMethod(Class<T> type, Class<R> returnType, final String name, final Object... parameterTypes) {
        Method method = JavaReflectionUtil.findMethod(type, new Spec<Method>() {
            @Override
            public boolean isSatisfiedBy(Method method) {
                return method.getName().equals(name) && Arrays.equals(method.getParameterTypes(), parameterTypes);
            }
        });
        return JavaReflectionUtil.method(type, returnType, method);
    }

    private PrintStream getDummyPrintStream() {
        OutputStream os = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                // do nothing
            }
        };
        return new PrintStream(os);
    }
}
