package io.muserver.rest;

import io.muserver.Method;
import io.muserver.openapi.TagObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ParamConverterProvider;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

class ResourceClass {

    final UriPattern pathPattern;
    private final Class<?> resourceClass;
    final Object resourceInstance;
    final List<MediaType> produces;
    final List<MediaType> consumes;
    List<ResourceMethod> resourceMethods;
    final String pathTemplate;
    final TagObject tag;
    final List<Class<? extends Annotation>> nameBindingAnnotations;
    private final SchemaObjectCustomizer schemaObjectCustomizer;

    /**
     * If this class is sub-resource, then this is the locator method. Otherwise null.
     */
    final ResourceMethod locatorMethod;

    private ResourceClass(UriPattern pathPattern, String pathTemplate, Class<?> resourceClass, Object resourceInstance, List<MediaType> consumes, List<MediaType> produces, TagObject tag, List<Class<? extends Annotation>> nameBindingAnnotations, SchemaObjectCustomizer schemaObjectCustomizer, ResourceMethod locatorMethod) {
        this.pathPattern = pathPattern;
        this.pathTemplate = pathTemplate;
        this.resourceClass = resourceClass;
        this.resourceInstance = resourceInstance;
        this.consumes = consumes;
        this.produces = produces;
        this.tag = tag;
        this.nameBindingAnnotations = nameBindingAnnotations;
        this.schemaObjectCustomizer = schemaObjectCustomizer;
        this.locatorMethod = locatorMethod;
    }

    public boolean matches(URI uri) {
        return pathPattern.matcher(uri).prefixMatches();
    }

    Set<ResourceMethod> nonSubResourceMethods() {
        return resourceMethods.stream().filter(resourceMethod -> !resourceMethod.isSubResource()).collect(Collectors.toSet());
    }

    Set<ResourceMethod> subResourceMethods() {
        return resourceMethods.stream().filter(ResourceMethod::isSubResource).collect(Collectors.toSet());
    }

    private void setupMethodInfo(List<ParamConverterProvider> paramConverterProviders) {
        if (resourceMethods != null) {
            throw new IllegalStateException("Cannot call setupMethodInfo twice");
        }

        List<ResourceMethod> resourceMethods = new ArrayList<>();
        java.lang.reflect.Method[] methods = this.resourceClass.getMethods();
        for (java.lang.reflect.Method restMethod : methods) {
            java.lang.reflect.Method annotationSource = JaxMethodLocator.getMethodThatHasJaxRSAnnotations(restMethod);
            Method httpMethod = ResourceMethod.getMuMethod(annotationSource);
            restMethod.setAccessible(true);
            Path methodPath = annotationSource.getAnnotation(Path.class);
            if (methodPath == null && httpMethod == null) {
                continue; // after this, only methods that are (sub)resource-methods or resource locators are processed
            }

            List<Class<? extends Annotation>> methodNameBindingAnnotations = getNameBindingAnnotations(annotationSource);

            UriPattern methodPattern = methodPath == null ? null : UriPattern.uriTemplateToRegex(methodPath.value());


            List<MediaType> methodProduces = MediaTypeDeterminer.supportedProducesTypes(annotationSource);
            List<MediaType> methodConsumes = MediaTypeDeterminer.supportedConsumesTypes(annotationSource);
            List<ResourceMethodParam> params = new ArrayList<>();
            Parameter[] parameters = annotationSource.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                Parameter p = parameters[i];
                ResourceMethodParam resourceMethodParam = ResourceMethodParam.fromParameter(i, p, paramConverterProviders, methodPattern);
                params.add(resourceMethodParam);
            }
            DescriptionData descriptionData = DescriptionData.fromAnnotation(restMethod, null);
            String pathTemplate = methodPath == null ? null : methodPath.value();
            boolean isDeprecated = annotationSource.isAnnotationPresent(Deprecated.class);
            resourceMethods.add(new ResourceMethod(this, methodPattern, restMethod, params, httpMethod, pathTemplate, methodProduces, methodConsumes, schemaObjectCustomizer, descriptionData, isDeprecated, methodNameBindingAnnotations, annotationSource.getAnnotations()));
        }
        this.resourceMethods = Collections.unmodifiableList(resourceMethods);
    }

    static List<Class<? extends Annotation>> getNameBindingAnnotations(AnnotatedElement annotationSource) {
        return Stream.of(annotationSource.getAnnotations())
            .filter(a -> a.annotationType().isAnnotationPresent(NameBinding.class))
            .map(Annotation::annotationType)
            .collect(toList());
    }

    static ResourceClass fromObject(Object restResource, List<ParamConverterProvider> paramConverterProviders, SchemaObjectCustomizer schemaObjectCustomizer) {
        Class<?> annotationSource = JaxClassLocator.getClassWithJaxRSAnnotations(restResource.getClass());
        if (annotationSource == null) {
            throw new IllegalArgumentException("The restResource class " + restResource.getClass().getName() + " must have a " + Path.class.getName() + " annotation to be eligible as a REST resource.");
        }

        // From section 3.6 of the spec:
        // JAX-RS annotations MAY be used on the methods and method parameters of a super-class or an implemented interface.
        // Such annotations are inherited by a corresponding sub-class or implementation class method provided that method
        // and its parameters do not have any JAX-RS annotations of its own. Annotations on a super-class take precedence
        // over those on an implemented interface. If a subclass or implementation method has any JAX-RS annotations then
        // all of the annotations on the super class or interface method are ignored.

        Path path = annotationSource.getDeclaredAnnotation(Path.class);
        if (path == null) {
            for (Annotation other : annotationSource.getDeclaredAnnotations()) {
                if (other.annotationType().getName().equals("javax.ws.rs.Path")) {
                    throw new IllegalArgumentException("The class " + annotationSource.getName() + " contains an old version " +
                        "of the JAX-RS implementation. The package name for JAX-RS resources has changed from 'javax.ws.rs' to " +
                        "'jakarta.ws.rs' in the 3.0.0 release of jakarta.ws.rs-api. Please change all references in your project to this new namespace in order to " +
                        "use the version of JAX-RS that mu-server implements (this may be as simple as doing a global find and " +
                        "replace for 'javax.ws.rs' to 'jakarta.ws.rs').");
                }
            }
            throw new IllegalArgumentException("The class " + annotationSource.getName() + " must specify a " + Path.class.getName()
                + " annotation because it has other JAX RS annotations declared. (Note that @Path cannot be inherited if there are other JAX RS annotations declared on this class.)");
        }

        UriPattern pathPattern = UriPattern.uriTemplateToRegex(path.value());

        List<MediaType> producesList = getProduces(null, annotationSource);
        List<MediaType> consumesList = getConsumes(null, annotationSource);
        List<Class<? extends Annotation>> classLevelNameBindingAnnotations = getNameBindingAnnotations(annotationSource);

        TagObject tag = DescriptionData.fromAnnotation(annotationSource, annotationSource.getSimpleName()).toTag();
        ResourceClass resourceClass = new ResourceClass(pathPattern, path.value(), restResource.getClass(), restResource, consumesList, producesList, tag, classLevelNameBindingAnnotations, schemaObjectCustomizer, null);
        resourceClass.setupMethodInfo(paramConverterProviders);
        return resourceClass;
    }

    private static List<MediaType> getProduces(List<MediaType> existing, Class<?> annotationSource) {
        Produces produces = annotationSource.getAnnotation(Produces.class);
        List<MediaType> producesList = new ArrayList<>(MediaTypeHeaderDelegate.fromStrings(produces == null ? null : asList(produces.value())));
        if (existing != null) {
            producesList.addAll(existing);
        }
        return producesList;
    }

    private static List<MediaType> getConsumes(List<MediaType> existing, Class<?> annotationSource) {
        Consumes consumes = annotationSource.getAnnotation(Consumes.class);
        List<MediaType> consumesList = new ArrayList<>(MediaTypeHeaderDelegate.fromStrings(consumes == null ? null : asList(consumes.value())));
        if (existing != null) {
            consumesList.addAll(existing);
        }
        return consumesList;
    }

    static ResourceClass forSubResourceLocator(ResourceMethod rm, Class<?> instanceClass, Object instance, SchemaObjectCustomizer schemaObjectCustomizer, List<ParamConverterProvider> paramConverterProviders) {
        List<MediaType> existingConsumes = rm.effectiveConsumes.isEmpty() || (rm.directlyConsumes.isEmpty() && rm.effectiveConsumes.size() == 1 && rm.effectiveConsumes.get(0) == MediaType.WILDCARD_TYPE) ? null : rm.effectiveConsumes;
        List<MediaType> consumes = getConsumes(existingConsumes, instanceClass);
        List<MediaType> existingProduces = rm.effectiveProduces.isEmpty() || (rm.directlyProduces.isEmpty() && rm.effectiveProduces.size() == 1 && rm.effectiveProduces.get(0) == MediaType.WILDCARD_TYPE) ? null : rm.effectiveProduces;
        List<MediaType> produces = getProduces(existingProduces, instanceClass);
        ResourceClass resourceClass = new ResourceClass(rm.pathPattern, rm.pathTemplate, instanceClass, instance, consumes, produces, rm.resourceClass.tag, rm.resourceClass.nameBindingAnnotations, schemaObjectCustomizer, rm);
        resourceClass.setupMethodInfo(paramConverterProviders);
        return resourceClass;
    }


    @Override
    public String toString() {
        return "ResourceClass{" + resourceClassName() + '}';
    }

    String resourceClassName() {
        return resourceClass.getName();
    }
}
