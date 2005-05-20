/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
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
package org.apache.jackrabbit.taglib.bean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Bean creation based on class name.<br>
 * It creates a new instance on each call.<br>
 * 
 * @author <a href="mailto:edgarpoce@gmail.com">Edgar Poce </a>
 */
public class SimpleBeanFactory implements BeanFactory
{
    private static Log log = LogFactory.getLog(SimpleBeanFactory.class);

    /**
     * @param id
     * @return a new instance of the given class name
     */
    public Object getBean(String id)
    {
        try
        {
            ClassLoader tcl = Thread.currentThread().getContextClassLoader();
            Class beanClass = Class.forName(id);
            Object bean = beanClass.newInstance();
            return bean;
        } catch (Exception e)
        {
            log.error("Unable to create an instance of " + id, e);
            return null;
        }
    }
}