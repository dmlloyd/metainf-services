/*
 * The MIT License
 *
 * Copyright (c) 2009-, Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.kohsuke.metainf_services;

import static java.lang.Integer.signum;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import org.kohsuke.MetaInfServices;

/**
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings({"Since15"})
@SupportedAnnotationTypes("org.kohsuke.MetaInfServices")
public class AnnotationProcessorImpl extends AbstractProcessor {

    private static final Pattern PRIORITY_PATTERN = Pattern.compile("# priority (-?\\d+)");

    @Override public SourceVersion getSupportedSourceVersion() {
        try {
            // Seems to work.
            // Need to add unit tests. See stapler/stapler/core/src/test/java/org/kohsuke/stapler/jsr269/ for examples.
            return SourceVersion.valueOf("RELEASE_8");
        } catch (IllegalArgumentException x) {}
        try {
            return SourceVersion.valueOf("RELEASE_7");
        } catch (IllegalArgumentException x) {}
        return SourceVersion.RELEASE_6;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver())      return false;
        // TODO should not write anything until processingOver

        Map<String,Map<String, Registration>> services = new HashMap<String, Map<String, Registration>>();
        
        Elements elements = processingEnv.getElementUtils();
        Types types = processingEnv.getTypeUtils();

        TypeElement metaInfServicesTypeElement = elements.getTypeElement(MetaInfServices.class.getName());
        TypeMirror metaInfServicesType = metaInfServicesTypeElement.asType();

        // discover services from the current compilation sources
        outer: for (Element e : roundEnv.getElementsAnnotatedWith(metaInfServicesTypeElement)) {
            if (e.getKind().isClass() || e.getKind().isInterface()) {
                TypeElement typeElement = (TypeElement) e;
                String cn = null;
                int priority = 0;
                for (AnnotationMirror mirror : typeElement.getAnnotationMirrors()) {
                    DeclaredType annotationType = mirror.getAnnotationType();
                    if (types.isSameType(annotationType, metaInfServicesType)) {
                        // found it
                        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror.getElementValues().entrySet()) {
                            String elementName = entry.getKey().getSimpleName().toString();
                            AnnotationValue annotationValue = entry.getValue();
                            if (elementName.equals("value")) {
                                TypeMirror contractType = (TypeMirror) annotationValue.getValue();
                                if (! (contractType instanceof DeclaredType)) {
                                    processingEnv.getMessager().printMessage(Kind.ERROR, "Type '" + contractType + "' is a valid contract type", e, mirror, annotationValue);
                                    continue outer;
                                }
                                if (! types.isAssignable(typeElement.asType(), contractType)) {
                                    processingEnv.getMessager().printMessage(Kind.ERROR, "Type '" + typeElement.asType() + "' is not assignable to contract type '" + contractType + "'", e, mirror, annotationValue);
                                    continue outer;
                                }
                                cn = ((TypeElement) ((DeclaredType) contractType).asElement()).getQualifiedName().toString();
                            } else if (elementName.equals("priority")) {
                                priority = ((Integer) annotationValue.getValue()).intValue();
                            }
                        }
                        if (cn == null) {
                            // try to infer it
                            final TypeMirror superclass = typeElement.getSuperclass();
                            boolean hasBaseClass = superclass.getKind()!=TypeKind.NONE && !isObject(superclass);
                            boolean hasInterfaces = !typeElement.getInterfaces().isEmpty();
                            if(hasBaseClass^hasInterfaces) {
                                if(hasBaseClass)
                                    cn = ((TypeElement)((DeclaredType)typeElement.getSuperclass()).asElement()).getQualifiedName().toString();
                                else cn = ((TypeElement)((DeclaredType)typeElement.getInterfaces().get(0)).asElement()).getQualifiedName().toString();
                            }
                        }
                        if (cn == null) {
                            processingEnv.getMessager().printMessage(Kind.ERROR, "Cannot infer contract type for '" + typeElement + "'", typeElement, mirror);
                            continue outer;
                        }
                        Map<String, Registration> v = services.get(cn);
                        if(v==null)
                            services.put(cn,v=new HashMap<String, Registration>());
                        String name = elements.getBinaryName(typeElement).toString();
                        v.put(name, new Registration(priority, name));
                        // no need to look at more annotations
                        break;
                    }
                }
            }
        }

        // also load up any existing values, since this compilation may be partial
        Filer filer = processingEnv.getFiler();
        for (Map.Entry<String,Map<String, Registration>> e : services.entrySet()) {
            try {
                String contract = e.getKey();
                FileObject f = filer.getResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" +contract);
                BufferedReader r = new BufferedReader(new InputStreamReader(f.openInputStream(), "UTF-8"));
                try {
                    String line;
                    int currentPriority = 0;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        final Matcher matcher = PRIORITY_PATTERN.matcher(line);
                        if (matcher.matches()) {
                            currentPriority = Integer.parseInt(matcher.group(1));
                        }
                        line = line.replaceAll("#.*", "").trim();
                        if (!line.isEmpty()) {
                            e.getValue().put(line, new Registration(currentPriority, line));
                        }
                    }
                    r.close();
                } finally {
                    try {
                        r.close();
                    } catch (IOException ignored) {}
                }
            } catch (FileNotFoundException x) {
                // doesn't exist
            } catch (IOException x) {
                processingEnv.getMessager().printMessage(Kind.ERROR,"Failed to load existing service definition files: "+x);
            }
        }

        // now write them back out
        for (Map.Entry<String,Map<String, Registration>> e : services.entrySet()) {
            try {
                String contract = e.getKey();
                processingEnv.getMessager().printMessage(Kind.NOTE,"Writing META-INF/services/"+contract);
                FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" +contract);
                PrintWriter pw = new PrintWriter(new OutputStreamWriter(f.openOutputStream(), "UTF-8"));
                int currentPriority = 0;
                for (Registration value : new TreeSet<Registration>(e.getValue().values())) {
                    if (value.getPriority() != currentPriority) {
                        currentPriority = value.getPriority();
                        pw.printf("# priority %d%n", Integer.valueOf(currentPriority));
                    }
                    pw.println(value.getName());
                }
                pw.close();
            } catch (IOException x) {
                processingEnv.getMessager().printMessage(Kind.ERROR,"Failed to write service definition files: "+x);
            }
        }

        return false;
    }

    private boolean isObject(TypeMirror t) {
        if (t instanceof DeclaredType) {
            DeclaredType dt = (DeclaredType) t;
            return((TypeElement)dt.asElement()).getQualifiedName().toString().equals("java.lang.Object");
        }
        return false;
    }

    private static class Registration implements Comparable<Registration> {
        private final String name;
        private final int priority;

        Registration(final int priority, final String name) {
            this.priority = priority;
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int getPriority() {
            return priority;
        }

        public int compareTo(final Registration o) {
            int res = signum(o.priority - priority);
            if (res == 0) res = name.compareTo(o.name);
            return res;
        }
    }
}
