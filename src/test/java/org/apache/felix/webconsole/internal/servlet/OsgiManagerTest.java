/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.webconsole.internal.servlet;

import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class OsgiManagerTest {
    @Test
    public void testSplitCommaSeparatedString() {
        assertEquals(0, OsgiManager.splitCommaSeparatedString(null).size());
        assertEquals(0, OsgiManager.splitCommaSeparatedString("").size());
        assertEquals(0, OsgiManager.splitCommaSeparatedString(" ").size());
        assertEquals(Collections.singleton("foo.bar"),
                OsgiManager.splitCommaSeparatedString("foo.bar "));

        Set<String> expected = new HashSet<String>();
        expected.add("abc");
        expected.add("x.y.z");
        expected.add("123");
        assertEquals(expected,
                OsgiManager.splitCommaSeparatedString(" abc , x.y.z,123"));
    }

    @SuppressWarnings({ "unchecked", "rawtypes", "serial" })
    @Test
    public void testUpdateDependenciesCustomizerAdd() throws Exception {
        BundleContext bc = mockBundleContext();

        final List<Boolean> updateCalled = new ArrayList<Boolean>();
        OsgiManager mgr = new OsgiManager(bc) {
            void updateRegistrationState() {
                updateCalled.add(true);
            }
        };

        ServiceTrackerCustomizer stc = mgr.new UpdateDependenciesStateCustomizer();

        ServiceReference sref = Mockito.mock(ServiceReference.class);
        stc.addingService(sref);
        assertEquals(0, updateCalled.size());

        ServiceReference sref2 = Mockito.mock(ServiceReference.class);
        Mockito.when(sref2.getProperty(OsgiManager.SECURITY_PROVIDER_PROPERTY_NAME)).thenReturn("xyz");
        stc.addingService(sref2);
        assertEquals(Collections.singleton("xyz"), mgr.registeredSecurityProviders);
        assertEquals(1, updateCalled.size());
    }

    @SuppressWarnings({ "unchecked", "rawtypes", "serial" })
    @Test
    public void testUpdateDependenciesCustomzerRemove() throws Exception {
        BundleContext bc = mockBundleContext();

        final List<Boolean> updateCalled = new ArrayList<Boolean>();
        OsgiManager mgr = new OsgiManager(bc) {
            void updateRegistrationState() {
                updateCalled.add(true);
            }
        };
        mgr.registeredSecurityProviders.add("abc");
        mgr.registeredSecurityProviders.add("xyz");

        ServiceTrackerCustomizer stc = mgr.new UpdateDependenciesStateCustomizer();

        ServiceReference sref = Mockito.mock(ServiceReference.class);
        stc.removedService(sref, null);
        assertEquals(0, updateCalled.size());
        assertEquals(2, mgr.registeredSecurityProviders.size());
        assertTrue(mgr.registeredSecurityProviders.contains("abc"));
        assertTrue(mgr.registeredSecurityProviders.contains("xyz"));

        ServiceReference sref2 = Mockito.mock(ServiceReference.class);
        Mockito.when(sref2.getProperty(OsgiManager.SECURITY_PROVIDER_PROPERTY_NAME)).thenReturn("xyz");
        stc.removedService(sref2, null);
        assertEquals(Collections.singleton("abc"), mgr.registeredSecurityProviders);
        assertEquals(1, updateCalled.size());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    public void testUpdateDependenciesCustomzerModified() throws Exception {
        BundleContext bc = mockBundleContext();

        OsgiManager mgr = new OsgiManager(bc);

        final List<String> invocations = new ArrayList<String>();
        ServiceTrackerCustomizer stc = mgr.new UpdateDependenciesStateCustomizer() {
            @Override
            public Object addingService(ServiceReference reference) {
                invocations.add("added:" + reference);
                return null;
            }

            @Override
            public void removedService(ServiceReference reference, Object service) {
                invocations.add("removed:" + reference);
            }
        };

        ServiceReference sref = Mockito.mock(ServiceReference.class);
        Mockito.when(sref.toString()).thenReturn("blah!");

        assertEquals("Precondition", 0, invocations.size());
        stc.modifiedService(sref, null);
        assertEquals(2, invocations.size());
        assertEquals("removed:blah!", invocations.get(0));
        assertEquals("added:blah!", invocations.get(1));
    }


    @SuppressWarnings("serial")
    @Test
    public void testUpdateRegistrationStateNoRequiredProviders() throws Exception {
        BundleContext bc = mockBundleContext();

        final List<String> invocations = new ArrayList<String>();
        OsgiManager mgr = new OsgiManager(bc) {
            @Override
            protected synchronized void registerHttpService() {
                invocations.add("register");
            }

            @Override
            protected synchronized void unregisterHttpService() {
                invocations.add("unregister");
            }
        };

        // HTTP Service not present -> unregister
        mgr.updateRegistrationState();
        assertEquals(Collections.singletonList("unregister"), invocations);

        // HTTP Service present, no required providers, no registered providers -> register
        invocations.clear();
        mgr.registeredSecurityProviders.clear();
        mgr.requiredSecurityProviders.clear();
        setPrivateField(OsgiManager.class, mgr, "httpService", Mockito.mock(HttpService.class));
        mgr.updateRegistrationState();
        assertEquals(Collections.singletonList("register"), invocations);
    }

    @SuppressWarnings("serial")
    @Test
    public void testUpdateRegistrationStateSomeRequiredProviders() throws Exception {
        BundleContext bc = mockBundleContext();
        Mockito.when(bc.getProperty(OsgiManager.FRAMEWORK_PROP_SECURITY_PROVIDERS)).
            thenReturn("foo,blah");

        final List<String> invocations = new ArrayList<String>();
        OsgiManager mgr = new OsgiManager(bc) {
            @Override
            protected synchronized void registerHttpService() {
                invocations.add("register");
            }

            @Override
            protected synchronized void unregisterHttpService() {
                invocations.add("unregister");
            }
        };

        // HTTP Service present, some required providers, no registered providers -> unregister
        invocations.clear();
        mgr.registeredSecurityProviders.clear();
        setPrivateField(OsgiManager.class, mgr, "httpService", Mockito.mock(HttpService.class));
        mgr.updateRegistrationState();
        assertEquals(Collections.singletonList("unregister"), invocations);

        // HTTP Service present, some required providers, more registered ones -> register
        invocations.clear();
        mgr.registeredSecurityProviders.addAll(Arrays.asList("foo", "bar", "blah"));
        setPrivateField(OsgiManager.class, mgr, "httpService", Mockito.mock(HttpService.class));
        mgr.updateRegistrationState();
        assertEquals(Collections.singletonList("register"), invocations);

        // HTTP Service present, some required providers, different registered ones -> unregister
        invocations.clear();
        mgr.registeredSecurityProviders.clear();
        mgr.registeredSecurityProviders.addAll(Arrays.asList("foo", "bar"));
        setPrivateField(OsgiManager.class, mgr, "httpService", Mockito.mock(HttpService.class));
        mgr.updateRegistrationState();
        assertEquals(Collections.singletonList("unregister"), invocations);

        // HTTP Service not present, some required providers, more registered ones -> unregister
        invocations.clear();
        mgr.registeredSecurityProviders.addAll(Arrays.asList("foo", "bar", "blah"));
        setPrivateField(OsgiManager.class, mgr, "httpService", null);
        mgr.updateRegistrationState();
        assertEquals(Collections.singletonList("unregister"), invocations);
    }

    @SuppressWarnings("serial")
    @Test
    public void testBindService() throws Exception {
        BundleContext bc = mockBundleContext();

        final List<Boolean> updateCalled = new ArrayList<Boolean>();
        OsgiManager mgr = new OsgiManager(bc) {
            void updateRegistrationState() {
                updateCalled.add(true);
            }
        };

        assertEquals("Precondition", 0, updateCalled.size());

        HttpService svc = Mockito.mock(HttpService.class);
        mgr.bindHttpService(svc);
        assertSame(svc, getPrivateField(OsgiManager.class, mgr, "httpService"));
        assertEquals(1, updateCalled.size());

        updateCalled.clear();
        mgr.bindHttpService(null);
        assertSame(svc, getPrivateField(OsgiManager.class, mgr, "httpService"));
        assertEquals(0, updateCalled.size());
    }

    @SuppressWarnings("serial")
    @Test
    public void testUnbindService() throws Exception {
        BundleContext bc = mockBundleContext();

        final List<Boolean> updateCalled = new ArrayList<Boolean>();
        OsgiManager mgr = new OsgiManager(bc) {
            void updateRegistrationState() {
                updateCalled.add(true);
            }
        };

        HttpService svc = Mockito.mock(HttpService.class);
        mgr.bindHttpService(svc);
        assertSame(svc, getPrivateField(OsgiManager.class, mgr, "httpService"));
        assertEquals(1, updateCalled.size());

        updateCalled.clear();
        mgr.unbindHttpService(null);
        assertEquals(0, updateCalled.size());
        assertSame(svc, getPrivateField(OsgiManager.class, mgr, "httpService"));

        updateCalled.clear();
        // unbind a different service, this should be ignored
        mgr.unbindHttpService(Mockito.mock(HttpService.class));
        assertEquals(0, updateCalled.size());
        assertSame(svc, getPrivateField(OsgiManager.class, mgr, "httpService"));

        updateCalled.clear();
        // unbind the bound service, this should remove it
        mgr.unbindHttpService(svc);
        assertEquals(1, updateCalled.size());
        assertNull(getPrivateField(OsgiManager.class, mgr, "httpService"));
    }

    private Object getPrivateField(Class<?> cls, Object obj, String field) throws Exception {
        Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(obj);
    }

    private void setPrivateField(Class<?> cls, Object obj, String field, Object value) throws Exception {
        Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private BundleContext mockBundleContext() throws InvalidSyntaxException {
        Bundle bundle = Mockito.mock(Bundle.class);
        BundleContext bc = Mockito.mock(BundleContext.class);
        Mockito.when(bc.getBundle()).thenReturn(bundle);
        Mockito.when(bundle.getBundleContext()).thenReturn(bc);
        Mockito.when(bc.createFilter(Mockito.anyString())).then(new Answer<Filter>() {
            @Override
            public Filter answer(InvocationOnMock invocation) throws Throwable {
                String fs = invocation.getArgumentAt(0, String.class);
                return FrameworkUtil.createFilter(fs);
            }
        });
        return bc;
    }
}
