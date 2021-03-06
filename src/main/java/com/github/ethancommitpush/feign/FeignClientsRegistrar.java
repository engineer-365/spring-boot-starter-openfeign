/**
 * Copyright 2020 Yisin Lin
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.github.ethancommitpush.feign;

import com.github.ethancommitpush.feign.annotation.FeignClient;
import com.github.ethancommitpush.feign.decoder.CustomErrorDecoder;
import feign.Feign;
import feign.Logger;
import feign.codec.Encoder;
import feign.httpclient.ApacheHttpClient;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLContext;
import java.beans.Introspector;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Registrar to register {@link com.github.ethancommitpush.feign.annotation.FeignClient}s.
 */
public class FeignClientsRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, BeanFactoryAware, EnvironmentAware {

    private static final String BASE_PACKAGES_KEY = "feign.base-packages";
    private static final String LOG_LEVEL_KEY = "feign.log-level";

    private Environment environment;
    private ResourceLoader resourceLoader;
    private BeanFactory beanFactory;

    /**
     * Trigger registering feign clients, but actually metadata and registry are not used at all.
     * @param metadata annotation metadata of the importing class.
     * @param registry current bean definition registry.
     * @see org.springframework.context.annotation.ImportBeanDefinitionRegistrar
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        registerFeignClients();
    }

    /**
     * Scan all interfaces declared with &#64;FeignClient and collect className, attributes, and logLevel.
     */
    public void registerFeignClients() {
        ClassPathScanningCandidateComponentProvider scanner = getScanner();
        scanner.setResourceLoader(this.resourceLoader);

        AnnotationTypeFilter annotationTypeFilter = new AnnotationTypeFilter(FeignClient.class);
        scanner.addIncludeFilter(annotationTypeFilter);
        List<String> basePackages = Optional.ofNullable(environment.getProperty(BASE_PACKAGES_KEY))
                .map(s -> Arrays.asList(s.split("\\,"))).orElse(Collections.emptyList());
        String logLevel = Optional.ofNullable(environment.getProperty(LOG_LEVEL_KEY))
                .orElse(Logger.Level.BASIC.name());

        basePackages.stream()
                .map(p -> scanner.findCandidateComponents(p))
                .flatMap(Collection::stream)
                .filter(bd -> bd instanceof AnnotatedBeanDefinition)
                .map(bd -> (AnnotatedBeanDefinition) bd)
                .map(abd -> abd.getMetadata())
                .filter(meta -> meta.isInterface())
                .forEach(meta -> {
                    Map<String, Object> attributes = meta.getAnnotationAttributes(FeignClient.class.getCanonicalName());
                    registerFeignClient(meta.getClassName(), attributes, logLevel);
                });
    }

    /**
     * Register generated feign clients as singletons.
     * @param className class name of the interface which declared with &#64;FeignClient.
     * @param attributes attributes of the &#64;FeignClient annotation.
     * @param logLevel log level configured at property file or as default value: BASIC.
     */
    private void registerFeignClient(String className, Map<String, Object> attributes, String logLevel) {
        String shortClassName = ClassUtils.getShortName(className);
        String beanName =  Introspector.decapitalize(shortClassName);
        Encoder encoder = getEncoder(attributes);
        Class<?> apiType = null;
        try {
            apiType = Class.forName(className);
        } catch (Exception e) {

        }
        Object bean = feignBuild(apiType, resolve((String) attributes.get("url")), encoder, logLevel);
        ((ConfigurableListableBeanFactory) beanFactory).registerSingleton(beanName, bean);
    }

    /**
     * Generate feign client.
     * @param apiType class type of the interface which declared with &#64;FeignClient.
     * @param url url from the attributes of the &#64;FeignClient annotation.
     * @param encoder encoder from the attributes of the &#64;FeignClient annotation.
     * @param logLevel log level.
     * @return generated feign client.
     */
    private <T> T feignBuild(Class<T> apiType, String url, Encoder encoder, String logLevel) {
        Feign.Builder builder = Feign.builder()
                .client(new ApacheHttpClient(getHttpClient()))
                .errorDecoder(new CustomErrorDecoder())
                .decoder(new JacksonDecoder())
                .logger(new Logger.ErrorLogger())
                .logLevel(Logger.Level.valueOf(logLevel));
        builder.encoder(encoder != null ? encoder : new JacksonEncoder());

        return builder.target(apiType, url);
    }

    /**
     * Get a default httpClient which trust self-signed certificates.
     * @return default httpClient.
     */
    private CloseableHttpClient getHttpClient() {
        CloseableHttpClient httpClient = null;
        try {
            //To trust self-signed certificates
            TrustStrategy acceptingTrustStrategy = (X509Certificate[] chain, String authType) -> true;
            SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
            SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(sslContext);
            httpClient = HttpClients.custom().setSSLSocketFactory(csf).build();
        } catch (Exception e) {
        }
        return httpClient;
    }

    /**
     * Get the encoder value from the attributes of the &#64;FeignClient annotation.
     * @return encoder.
     */
    private Encoder getEncoder(Map<String, Object> attributes) {
        if (attributes.get("encoder") == null) {
            return null;
        }

        Encoder encoder = null;
        try {
            encoder = (Encoder) ((Class<?>) attributes.get("encoder")).newInstance();
        } catch (Exception e) {
        }
        return encoder;
    }

    /**
     * Get the value or resolve placeholders to find the value configured at the property file.
     * @return value.
     */
    private String resolve(String value) {
        if (StringUtils.hasText(value)) {
            return this.environment.resolvePlaceholders(value);
        }
        return value;
    }

    /**
     * Get the class path scanner.
     * @return scanner.
     */
    private ClassPathScanningCandidateComponentProvider getScanner() {
        return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                if (!beanDefinition.getMetadata().isIndependent()) {
                    return false;
                }
                return !beanDefinition.getMetadata().isAnnotation();
            }
        };
    }

    /**
     * Set the ResourceLoader that this object runs in.
     * @param resourceLoader the ResourceLoader object to be used by this object.
     * @see org.springframework.context.ResourceLoaderAware
     */
    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * Supply the owning factory to a bean instance.
     * @param beanFactory owning BeanFactory (never {@code null}).
     * The bean can immediately call methods on the factory.
     * @throws BeansException in case of initialization errors
     * @see org.springframework.beans.factory.BeanFactoryAware
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    /**
     * Set the {@code Environment} that this component runs in.
     * @see org.springframework.context.EnvironmentAware
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

}