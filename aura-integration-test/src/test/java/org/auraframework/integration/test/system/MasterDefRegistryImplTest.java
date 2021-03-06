/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.integration.test.system;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.auraframework.adapter.ConfigAdapter;
import org.auraframework.adapter.RegistryAdapter;
import org.auraframework.cache.Cache;
import org.auraframework.def.ApplicationDef;
import org.auraframework.def.ClientLibraryDef;
import org.auraframework.def.ComponentDef;
import org.auraframework.def.ControllerDef;
import org.auraframework.def.DefDescriptor;
import org.auraframework.def.DefDescriptor.DefType;
import org.auraframework.def.Definition;
import org.auraframework.def.DefinitionAccess;
import org.auraframework.def.DescriptorFilter;
import org.auraframework.def.HelperDef;
import org.auraframework.def.RendererDef;
import org.auraframework.def.StyleDef;
import org.auraframework.def.TypeDef;
import org.auraframework.impl.AuraImplTestCase;
import org.auraframework.impl.system.MasterDefRegistryImpl;
import org.auraframework.instance.BaseComponent;
import org.auraframework.service.CachingService;
import org.auraframework.service.DefinitionService;
import org.auraframework.service.LoggingService;
import org.auraframework.system.AuraContext;
import org.auraframework.system.AuraContext.Authentication;
import org.auraframework.system.AuraContext.Format;
import org.auraframework.system.AuraContext.Mode;
import org.auraframework.system.DefRegistry;
import org.auraframework.system.DependencyEntry;
import org.auraframework.system.Location;
import org.auraframework.system.MasterDefRegistry;
import org.auraframework.system.Source;
import org.auraframework.system.SourceListener;
import org.auraframework.system.SourceLoader;
import org.auraframework.system.SubDefDescriptor;
import org.auraframework.test.source.StringSourceLoader;
import org.auraframework.test.source.StringSourceLoader.NamespaceAccess;
import org.auraframework.test.util.AuraTestingUtil;
import org.auraframework.throwable.NoAccessException;
import org.auraframework.throwable.quickfix.DefinitionNotFoundException;
import org.auraframework.throwable.quickfix.InvalidDefinitionException;
import org.auraframework.throwable.quickfix.QuickFixException;
import org.auraframework.util.json.Json;
import org.auraframework.util.test.annotation.ThreadHostileTest;
import org.auraframework.util.test.annotation.UnAdaptableTest;
import org.auraframework.util.test.util.AuraPrivateAccessor;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.internal.util.MockUtil;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@ThreadHostileTest("Don't you go clearing my caches.")
public class MasterDefRegistryImplTest extends AuraImplTestCase {
    @Inject
    private CachingService cachingService;

    @Inject
    private ConfigAdapter configAdapter;

    @Inject
    private LoggingService loggingService;

    @Inject
    private Collection<RegistryAdapter> providers;

    @Mock
    Definition globalDef;
    @Mock
    DefinitionAccess defAccess;
    @Mock
    DefDescriptor<ComponentDef> referencingDesc;
    @Mock
    Cache<String, String> mockAccessCheckCache;

    private DefRegistry<?>[] getRegistries(Mode mode, Authentication access, Set<SourceLoader> loaders,
                                           boolean asMocks) {
        List<DefRegistry<?>> ret = Lists.newArrayList();
        for (RegistryAdapter provider : providers) {
            DefRegistry<?>[] registries = provider.getRegistries(mode, access, loaders);
            if (registries != null) {
                for (DefRegistry<?> reg : registries) {
                    Set<String> ns = reg.getNamespaces();

                    if (ns != null) {
                        ret.add(asMocks ? Mockito.spy(reg) : reg);
                    }
                }
            }
        }
        return ret.toArray(new DefRegistry[ret.size()]);
    }

    private MasterDefRegistryImplOverride getDefRegistry(boolean asMocks) {
        AuraContext context = contextService.getCurrentContext();
        MasterDefRegistryImplOverride registry = new MasterDefRegistryImplOverride(getRegistries(context.getMode(),
                context.getAccess(), null, asMocks));
        registry.setContext(context);
        return asMocks ? Mockito.spy(registry) : registry;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void spyOnDefs(final MasterDefRegistryImplOverride registry) throws QuickFixException {
        final MockUtil mockUtil = new MockUtil();
        for (DefRegistry<?> subReg : registry.getAllRegistries()) {
            Mockito.doAnswer(new Answer<Definition>() {
                @Override
                public Definition answer(InvocationOnMock invocation) throws Throwable {
                    Definition ret = (Definition) invocation.callRealMethod();
                    if (ret == null) {
                        return ret;
                    }
                    if (mockUtil.isMock(ret)) {
                        return ret;
                    } else {
                        ret = Mockito.spy(ret);
                        contextService.getCurrentContext().addDynamicDef(ret);
                        return ret;
                    }
                }
            }).when(subReg).getDef(Mockito.<DefDescriptor> any());
        }
    }

    /**
     * Verify some of the assertions (copied here) made by compileDef (excluding #2 & #5).
     * <ol>
     * <li>Each definition has 'validateDefinition()' called on it exactly once.</li>
     * <li>No definition is marked as valid until all definitions in the dependency set have been validated</li>
     * <li>Each definition has 'validateReferences()' called on it exactly once, after the definitions have been put in
     * local cache</li>
     * <li>All definitions are marked valid by the DefRegistry after the validation is complete</li>
     * <li>No definition should be available to other threads until it is marked valid</li>
     * <ol>
     */
    private void assertCompiledDef(Definition def) throws QuickFixException {
        Mockito.verify(def, Mockito.times(1)).validateDefinition();
        Mockito.verify(def, Mockito.times(1)).validateReferences();
        Mockito.verify(def, Mockito.times(1)).markValid();
        assertEquals("definition not valid: " + def, true, def.isValid());
    }

    private void assertIdenticalDependencies(DefDescriptor<?> desc1, DefDescriptor<?> desc2) throws Exception {
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        Set<DefDescriptor<?>> deps1 = registry.getDependencies(registry.getUid(null, desc1));
        Set<DefDescriptor<?>> deps2 = registry.getDependencies(registry.getUid(null, desc2));
        assertNotNull(deps1);
        assertNotNull(deps2);
        assertEquals("Descriptors should have the same number of dependencies", deps1.size(), deps2.size());

        // Loop through and check individual dependencies. Order doesn't matter.
        for (DefDescriptor<?> dep : deps1) {
            assertTrue("Descriptors do not have identical dependencies",
                    checkDependenciesContains(deps2, dep.getQualifiedName()));
        }
    }

    private boolean checkDependenciesContains(Set<DefDescriptor<?>> deps, String depSearch) {
        for (DefDescriptor<?> dep : deps) {
            if (dep.getQualifiedName().equals(depSearch)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testFindRegex() throws Exception {
        String namespace = "testFindRegex" + getAuraTestingUtil().getNonce();
        DefDescriptor<ApplicationDef> houseboat = addSourceAutoCleanup(ApplicationDef.class,
                String.format(baseApplicationTag, "", ""), String.format("%s:houseboat", namespace));
        addSourceAutoCleanup(ApplicationDef.class, String.format(baseApplicationTag, "", ""),
                String.format("%s:houseparty", namespace));
        addSourceAutoCleanup(ApplicationDef.class, String.format(baseApplicationTag, "", ""),
                String.format("%s:pantsparty", namespace));

        MasterDefRegistryImplOverride masterDefReg = getDefRegistry(false);

        assertTrue("find() not finding all sources",
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:*", namespace))).size() == 3);
        assertEquals("find() fails with wildcard as prefix", 1,
                masterDefReg.find(new DescriptorFilter("*://" + houseboat.getDescriptorName())).size());
        assertEquals("find() fails with wildcard as namespace", 1,
                masterDefReg.find(new DescriptorFilter("markup://*:" + houseboat.getName())).size());
        assertEquals("find() fails with wildcard as name", 1,
                masterDefReg.find(new DescriptorFilter(houseboat.getQualifiedName())).size());
        assertEquals("find() fails with wildcard at end of name", 2,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:house*", namespace))).size());
        assertEquals("find() fails with wildcard at beginning of name", 2,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:*party*", namespace))).size());

        assertEquals("find() should not find nonexistent name", 0,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:househunters", namespace))).size());
        assertEquals("find() should not find nonexistent name ending with wildcard", 0,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:househunters*", namespace))).size());
        assertEquals("find() should not find nonexistent name with preceeding wildcard", 0,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:*notherecaptain", namespace))).size());
    }

    /**
     * TODO: remove try block when find can handle DefType without a default prefix
     */
    @Test
    public void testFindDefTypeWithoutDefaultPrefix() throws Exception {
        MasterDefRegistryImplOverride masterDefReg = getDefRegistry(false);
        try {
            assertEquals("find() not expecting any matches", 0,
                    masterDefReg.find(new DescriptorFilter("*://doesnt:exist", DefType.TESTCASE)).size());
            fail("If you have fixed this bug, please update the expected results (remove the try block)");
        } catch (NullPointerException npe) {
            // this shouldn't happen and should be fixed
        }
    }

    @Test
    public void testFindDefTypeWithDefaultPrefix() throws Exception {
        MasterDefRegistryImplOverride masterDefReg = getDefRegistry(false);
        DefDescriptor<ApplicationDef> target = addSourceAutoCleanup(ApplicationDef.class,
                String.format(baseApplicationTag, "", ""));
        assertEquals("unexpected matches found", 1,
                masterDefReg.find(new DescriptorFilter("*://" + target.getDescriptorName(), DefType.APPLICATION))
                        .size());
    }

    private static class AddableDef<T extends Definition> {
        private final Class<T> defClass;
        private final String format;
        private final String content;

        public AddableDef(Class<T> defClass, String format, String content) {
            this.defClass = defClass;
            this.format = format;
            this.content = content;
        }

        public Class<T> getDefClass() {
            return this.defClass;
        }

        public String getFQN(String namespace, String name) {
            return String.format(this.format, namespace, name);
        }

        public String getContent() {
            return content;
        }
    }

    private static AddableDef<?> addable[] = new AddableDef[] {
            // Ignoring top level bundle defs.
            // APPLICATION(ApplicationDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
            // COMPONENT(ComponentDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
            // EVENT(EventDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
            // INTERFACE(InterfaceDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
            // LAYOUTS(LayoutsDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
            new AddableDef<>(ControllerDef.class, "js://%s.%s",
                    "({method: function(cmp) {}})"),
            new AddableDef<>(HelperDef.class, "js://%s.%s",
                    "({method: function(cmp) {}})"),
            // new AddableDef<ProviderDef>(ProviderDef.class, "js://%s.%s",
            // "({provide: function(cmp) {}})"),
            new AddableDef<>(RendererDef.class, "js://%s.%s",
                    "({render: function(cmp) {}})"),
            new AddableDef<>(StyleDef.class, "css://%s.%s",
                    ".THIS {display:block;}"),
            // Ignoring TESTSUITE(TestSuiteDef.class, Format.JS, DefDescriptor.JAVASCRIPT_PREFIX, "."),
            // Ignoring TOKENS(TokensDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":");
    };

    private MasterDefRegistry resetDefRegistry() {
        if (contextService.isEstablished()) {
            contextService.endContext();
        }
        contextService.startContext(Mode.UTEST, Format.JSON, Authentication.AUTHENTICATED);
        return contextService.getCurrentContext().getDefRegistry();
    }

    private <T extends Definition> void checkAddRemove(DefDescriptor<?> tld, String suid,
            AddableDef<T> toAdd) throws QuickFixException {
        DefDescriptor<T> dd;
        String uid, ouid;
        Set<DefDescriptor<?>> deps;
        AuraTestingUtil util = getAuraTestingUtil();
        MasterDefRegistry mdr;

        dd = definitionService.getDefDescriptor(toAdd.getFQN(tld.getNamespace(), tld.getName()),
                toAdd.getDefClass());
        util.addSourceAutoCleanup(dd, toAdd.getContent());
        mdr = resetDefRegistry();
        uid = mdr.getUid(null, tld);
        assertFalse("UID should change on add for " + dd.getDefType() + "@" + dd, suid.equals(uid));
        deps = mdr.getDependencies(uid);
        assertTrue("dependencies should contain the newly created " + dd.getDefType() + "@" + dd,
                deps.contains(dd));
        ouid = uid;
        util.removeSource(dd);
        mdr = resetDefRegistry();
        uid = mdr.getUid(null, tld);
        assertNotSame("UID should change on removal for " + dd.getDefType() + "@" + dd, ouid, uid);
        deps = mdr.getDependencies(uid);
        assertFalse("dependencies should not contain the deleted " + dd, deps.contains(dd));
    }

    private <T extends Definition> void checkOneTLD(String fqn, Class<T> clazz, String content)
            throws QuickFixException {
        AuraTestingUtil util = getAuraTestingUtil();
        String uid;

        DefDescriptor<T> tld = definitionService.getDefDescriptor(fqn, clazz);
        util.addSourceAutoCleanup(tld, content);
        MasterDefRegistry mdr = resetDefRegistry();
        // prime the cache.
        uid = mdr.getUid(null, tld);
        assertNotNull(tld + " did not give us a UID", uid);
        for (AddableDef<?> adding : addable) {
            checkAddRemove(tld, uid, adding);
        }
        util.removeSource(tld);
    }

    @Test
    public void testComponentChChChChanges() throws Exception {
        checkOneTLD("markup://chchch:changes" + getAuraTestingUtil().getNonce(),
                ComponentDef.class, "<aura:component></aura:component>");
    }

    @Test
    public void testApplicationChChChChanges() throws Exception {
        checkOneTLD("markup://chchch:changes" + getAuraTestingUtil().getNonce(),
                ApplicationDef.class, "<aura:application></aura:application>");
    }

    @Test
    public void testGetUidClientOutOfSync() throws Exception {
        String namespace = "testStringCache" + getAuraTestingUtil().getNonce();
        String namePrefix = String.format("%s:houseboat", namespace);
        DefDescriptor<ApplicationDef> houseboat = addSourceAutoCleanup(ApplicationDef.class,
                String.format(baseApplicationTag, "", ""), namePrefix);
        MasterDefRegistryImplOverride masterDefReg = getDefRegistry(false);
        String uid = masterDefReg.getUid(null, houseboat);
        assertNotNull(uid);
        // Check unchanged app gets same UID value
        assertEquals(uid, masterDefReg.getUid(uid, houseboat));

        //
        // When given an incorrect UID, masterDefReg simply returns the correct one.
        String newUid = masterDefReg.getUid(uid + " or not", houseboat);
        assertEquals(uid, newUid);
    }

    /**
     * Verify getting the UID of a dependency doesn't affect the original UID.
     */
    @Test
    public void testUidDependencies() throws Exception {
        DefDescriptor<ComponentDef> child = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component></aura:component>", "testUidDependenciesChild");
        DefDescriptor<ApplicationDef> parent = addSourceAutoCleanup(ApplicationDef.class,
                "<aura:application><" + child.getDescriptorName() + "/></aura:application>",
                "testUidDependenciesParent");

        MasterDefRegistryImplOverride masterDefReg1 = getDefRegistry(false);
        String parentUid1 = masterDefReg1.getUid(null, parent);

        MasterDefRegistryImplOverride masterDefReg2 = getDefRegistry(false);
        masterDefReg2.getUid(null, child);
        String parentUid2 = masterDefReg2.getUid(null, parent);

        assertTrue("UIDs do not match after getting a dependencies UID", parentUid1.equals(parentUid2));
    }

    /**
     * Verify UID values and dependencies against a gold file.
     *
     * This does a recursive set of dependencies checks to build a gold file with the resulting descriptors and UIDs to
     * ensure that we get both a valid set and can tell what changed (and thus verify that it should have changed).
     *
     * The format of the file is:
     * <ul>
     * <li>Top level descriptor ':' global UID.
     * <li>
     * <li>dependency ':' own hash
     * <li>
     * <li>...</li>
     * </ul>
     */
    @Test
    public void testUidValue() throws Exception {
        StringBuilder buffer = new StringBuilder();
        String cmpName = "ui:outputNumber";
        DefDescriptor<ComponentDef> desc = definitionService
                .getDefDescriptor(cmpName, ComponentDef.class);
        MasterDefRegistryImplOverride masterDefReg = getDefRegistry(false);
        String uid = masterDefReg.getUid(null, desc);
        assertNotNull("Could not retrieve UID for component " + cmpName, uid);
        Set<DefDescriptor<?>> dependencies = masterDefReg.getDependencies(uid);
        assertNotNull("Could not retrieve dependencies for component " + cmpName, dependencies);

        buffer.append(desc.toString());
        buffer.append(" : ");
        buffer.append(uid);
        buffer.append("\n");

        for (DefDescriptor<?> dep : dependencies) {
            buffer.append(dep);
            buffer.append(" : ");
            buffer.append(masterDefReg.getDef(dep).getOwnHash());
            buffer.append("\n");
        }
        goldFileText(buffer.toString());
    }

    @Test
    public void testGetUidDescriptorNull() throws Exception {
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        assertNull(registry.getUid(null, null));
    }

    @Test
    public void testGetUidDescriptorDoesntExist() throws Exception {
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        assertNull(registry.getUid(null, definitionService.getDefDescriptor("unknown:soldier", ComponentDef.class)));
    }

    @Test
    public void testGetUidLocalDef() throws Exception {
        DefDescriptor<ComponentDef> desc = definitionService.getDefDescriptor("twiddledee:twiddledumb", ComponentDef.class);
        ComponentDef def = Mockito.mock(ComponentDef.class);
        Mockito.when(def.getDescriptor()).thenReturn(desc);

        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        contextService.getCurrentContext().addDynamicDef(def);
        assertNotNull(registry.getUid(null, def.getDescriptor()));
    }

    @Test
    public void testGetUidSameAcrossInstances() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImplOverride registry1 = getDefRegistry(false);
        String uid1 = registry1.getUid(null, cmpDesc);
        MasterDefRegistryImplOverride registry2 = getDefRegistry(false);
        String uid2 = registry2.getUid(null, cmpDesc);
        assertEquals("Expected same UID for def from separate registry instances", uid1, uid2);
    }

    @Test
    public void testGetUidUnique() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc2 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        String uid1 = registry.getUid(null, cmpDesc1);
        String uid2 = registry.getUid(null, cmpDesc2);
        assertTrue("Components with same markup and dependencies should have different UIDs", !uid1.equals(uid2));
    }

    @Test
    @Ignore("Cache is held on context now")
    public void testGetUidCachedForChangedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        String uid = registry.getUid(null, cmpDesc);

        // UID cached for current registry
        registry.getSource(cmpDesc).addOrUpdate(
                "<aura:component><aura:attribute name='str' type='String'/></aura:component>");
        String uidNew = registry.getUid(null, cmpDesc);
        assertEquals("UID not cached", uid, uidNew);

        // UID not cached for new registry
        MasterDefRegistryImplOverride registryNext = getDefRegistry(false);
        String uidNext = registryNext.getUid(null, cmpDesc);
        assertFalse("UID not cached in new registry", uid.equals(uidNext));
    }

    @Test
    @Ignore("Cache is held on context now")
    public void testGetUidCachedForRemovedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>", null);
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        String uid = registry.getUid(null, cmpDesc);

        // UID cached for current registry
        getAuraTestingUtil().removeSource(cmpDesc);
        String uidNew = registry.getUid(null, cmpDesc);
        assertEquals("UID not cached", uid, uidNew);

        // UID not cached for new registry
        MasterDefRegistryImplOverride registryNext = getDefRegistry(false);
        String uidNext = registryNext.getUid(null, cmpDesc);
        assertNull("UID cached in new registry", uidNext);
    }

    @Test
    public void testGetUidForQuickFixException() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component><unknown:component/></aura:component>", null);
        MasterDefRegistryImplOverride registry = getDefRegistry(true);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected DefinitionNotFoundException");
        } catch (DefinitionNotFoundException e) {
            checkExceptionStart(e, null, "No COMPONENT named markup://unknown:component found");
        }
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(cmpDesc));

        // another request for getUid will not re-compile
        Mockito.reset(registry);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected DefinitionNotFoundException");
        } catch (DefinitionNotFoundException e) {
            checkExceptionStart(e, null, "No COMPONENT named markup://unknown:component found");
        }
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));
    }

    @Test
    public void testGetUidForNonQuickFixException() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component invalidAttribute=''/>", null);
        MasterDefRegistryImplOverride registry = getDefRegistry(true);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected InvalidDefinitionException");
        } catch (Throwable t) {
            checkExceptionContains(t, InvalidDefinitionException.class,
                    "Invalid attribute \"invalidAttribute\"");
        }

        // another request for getUid will not re-compile again
        Mockito.reset(registry);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected InvalidDefinitionException");
        } catch (Throwable e) {
            checkExceptionContains(e, InvalidDefinitionException.class,
                    "Invalid attribute \"invalidAttribute\"");
        }
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));
    }

    @Test
    public void testCompileDef() throws Exception {
        // create test component with 2 explicit dependencies
        DefDescriptor<ComponentDef> cmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc2 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(
                ComponentDef.class,
                String.format(baseComponentTag, "",
                        String.format("<%s/><%s/>", cmpDesc1.getDescriptorName(), cmpDesc2.getDescriptorName())));

        // spy on MDR
        final MasterDefRegistryImplOverride registry = getDefRegistry(true);
        spyOnDefs(registry);

        // get def UID to trigger compileDef, etc.
        String uid = registry.getUid(null, cmpDesc);
        assertNotNull(uid);
        ComponentDef def = registry.getDef(cmpDesc);
        assertNotNull(def);
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(cmpDesc));
        assertCompiledDef(def);

        // 
        // check direct dependencies
        // We have no way of knowing if other definitions will be compiled or will
        // come from static registries, so don't check.
        //
        ComponentDef def1 = registry.getDef(cmpDesc1);
        assertNotNull(def1);
        assertCompiledDef(def1);

        ComponentDef def2 = registry.getDef(cmpDesc2);
        assertNotNull(def2);
        assertCompiledDef(def2);
                }

    @Test
    public void testCompileDefLocalDef() throws Exception {
        // build a mock def
        String descName = String.format("%s:ghost", System.nanoTime());
        ComponentDef def = Mockito.mock(ComponentDef.class);

        Mockito.doReturn(definitionService.getDefDescriptor(descName, ComponentDef.class)).when(def).getDescriptor();

        // spy on MDR's registries to spy on defs
        final MasterDefRegistryImplOverride registry = getDefRegistry(true);
        spyOnDefs(registry);
        contextService.getCurrentContext().addDynamicDef(def);

        // get def UID to trigger compileDef, etc.
        String uid = registry.getUid(null, def.getDescriptor());
        assertNotNull(uid);
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(def.getDescriptor()));
        Mockito.doReturn(true).when(def).isValid();
        assertCompiledDef(def);

        // check all dependencies
        MockUtil mockUtil = new MockUtil();
        Set<DefDescriptor<?>> dependencies = registry.getDependencies(uid);
        for (DefDescriptor<?> dep : dependencies) {
            Definition depDef = registry.getDef(dep);
            if (mockUtil.isMock(depDef)) {
                assertCompiledDef(depDef);
            }
        }
    }

    @Test
    public void testCompileDefOnlyOnce() throws Exception {
        // getDef on registry should compile the def
        String cmpContent = "<aura:component/>";
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, cmpContent);
        MasterDefRegistryImplOverride registry = getDefRegistry(true);
        registry.getDef(cmpDesc);
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(cmpDesc));

        // another getDef on same registry should not re-compile the def
        Mockito.reset(registry);
        assertNotNull(registry.getDef(cmpDesc));
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));

        // another getDef on other registry instance should now compile zero additional times
        registry = getDefRegistry(true);
        assertNotNull(registry.getDef(cmpDesc));
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));
    }

    @Test
    public void testGetDefDescriptorNull() throws Exception {
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        assertNull(registry.getDef(null));
    }

    @Test
    public void testGetDefDescriptorDoesntExist() throws Exception {
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        assertNull(registry.getDef(definitionService.getDefDescriptor("unknown:soldier", ComponentDef.class)));
    }

    @Test
    @Ignore("Cache is held on context now")
    public void testGetDefCachedForChangedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        ComponentDef def = registry.getDef(cmpDesc);
        assertNull(def.getAttributeDef("str"));

        // Definition cached for current registry
        registry.getSource(cmpDesc).addOrUpdate(
                "<aura:component><aura:attribute name='str' type='String'/></aura:component>");
        ComponentDef defNew = registry.getDef(cmpDesc);
        assertNull(defNew.getAttributeDef("str"));

        // Definition not cached for new registry
        MasterDefRegistryImplOverride registryNext = getDefRegistry(false);
        ComponentDef defNext = registryNext.getDef(cmpDesc);
        assertNotNull(defNext.getAttributeDef("str"));
    }

    @Test
    @Ignore("Cache is held on context now")
    public void testGetDefCachedForRemovedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>", null);
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        ComponentDef def = registry.getDef(cmpDesc);
        assertNotNull(def);

        // Definition cached for current registry
        getAuraTestingUtil().removeSource(cmpDesc);
        ComponentDef defNew = registry.getDef(cmpDesc);
        assertNotNull(defNew);

        // Definition not cached for new registry
        MasterDefRegistryImplOverride registryNext = getDefRegistry(false);
        ComponentDef defNext = registryNext.getDef(cmpDesc);
        assertNull(defNext);
    }

    @Test
    public void testGetRawDefReturnsNullForRemovedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>", null);
        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        ComponentDef def = registry.getRawDef(cmpDesc);
        assertNotNull(def);

        getAuraTestingUtil().removeSource(cmpDesc);
        def = registry.getRawDef(cmpDesc);
        assertNull(def);
    }

    /**
     * Circular dependencies case 1: A has inner component B, and B has an explicit dependency on A (via aura:dependency
     * tag)
     */
    @Test
    public void testCircularDependenciesInnerCmp() throws Exception {
        DefDescriptor<ComponentDef> cmpDescA = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDescB = addSourceAutoCleanup(
                ComponentDef.class,
                String.format("<aura:component><aura:dependency resource=\"%s\"/></aura:component>",
                        cmpDescA.getQualifiedName()));
        updateStringSource(cmpDescA,
                String.format("<aura:component><%s/></aura:component>", cmpDescB.getDescriptorName()));
        // Circular dependency cases should result in identical dependencies
        assertIdenticalDependencies(cmpDescA, cmpDescB);
    }

    /**
     * Circular dependencies case 2: D extends C, and C has explicit dependency on D (via aura:dependency tag)
     */
    @Test
    public void testCircularDependenciesExtendsCmp() throws Exception {
        DefDescriptor<ComponentDef> cmpDescC = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDescD = addSourceAutoCleanup(ComponentDef.class,
                String.format("<aura:component extends=\"%s\"/>", cmpDescC.getDescriptorName()));
        updateStringSource(cmpDescC, String.format(
                "<aura:component extensible=\"true\"><aura:dependency resource=\"%s\"/></aura:component>",
                cmpDescD.getQualifiedName()));
        // Circular dependency cases should result in identical dependencies
        assertIdenticalDependencies(cmpDescC, cmpDescD);
    }

    /**
     * Circular dependencies case 3: E has dependency on F, and F has dependency on E (both through aura:dependency tag)
     */
    @Test
    public void testCircularDependenciesDepTag() throws Exception {
        DefDescriptor<ComponentDef> cmpDescE = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDescF = addSourceAutoCleanup(
                ComponentDef.class,
                String.format("<aura:component><aura:dependency resource=\"%s\"/></aura:component>",
                        cmpDescE.getQualifiedName()));
        updateStringSource(
                cmpDescE,
                String.format("<aura:component><aura:dependency resource=\"%s\"/></aura:component>",
                        cmpDescF.getQualifiedName()));
        // Circular dependency cases should result in identical dependencies
        assertIdenticalDependencies(cmpDescE, cmpDescF);
    }

    /**
     * Verify correct dependencies are attached to a component.
     */
    @Test
    public void testGetDependencies() throws Exception {
        DefDescriptor<ComponentDef> depCmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> depCmpDesc2 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        // Manually add dependency to inner component
        DefDescriptor<ComponentDef> cmpDesc2 = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component><aura:dependency resource=\"" + depCmpDesc1.getQualifiedName()
                        + "\"/></aura:component>");
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(
                ComponentDef.class,
                String.format(
                        baseComponentTag,
                        "",
                        String.format("<aura:dependency resource=\"" + depCmpDesc2.getQualifiedName()
                                + "\"/><%s/><%s/>", cmpDesc1.getDescriptorName(), cmpDesc2.getDescriptorName())));

        MasterDefRegistryImplOverride registry = getDefRegistry(false);
        String uid = registry.getUid(null, cmpDesc);
        Set<DefDescriptor<?>> deps = registry.getDependencies(uid);
        assertTrue("Component should have dependency on aura:component by default",
                checkDependenciesContains(deps, "markup://aura:component"));
        assertTrue("Component should have dependency on aura:rootComponent by default",
                checkDependenciesContains(deps, "markup://aura:rootComponent"));
        assertTrue("Component should not have a dependency on aura:application",
                !checkDependenciesContains(deps, "markup://aura:application"));
        assertTrue("No dependency on self found in Component",
                checkDependenciesContains(deps, cmpDesc.getQualifiedName()));
        assertTrue("Dependency on inner component not found",
                checkDependenciesContains(deps, cmpDesc1.getQualifiedName()));
        assertTrue("Dependency on inner component not found",
                checkDependenciesContains(deps, cmpDesc2.getQualifiedName()));
        assertTrue("Explicitly declared dependency on inner component not found",
                checkDependenciesContains(deps, depCmpDesc1.getQualifiedName()));
        assertTrue("Explicitly declared dependency on top level component not found",
                checkDependenciesContains(deps, depCmpDesc2.getQualifiedName()));
    }

    /**
     * Verify caches are cleared after a source change to a component file. In this case only the component def itself
     * should be cleared from the cache.
     */
    @ThreadHostileTest("requires cache to remain stable")
    @Test
    public void testInvalidateCacheCmpFile() throws Exception {
        MasterDefRegistryImplOverride mdr = getDefRegistry(false);

        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches();
        DefDescriptor<?> cmpDef = defs.get(DefType.COMPONENT);
        cachingService.notifyDependentSourceChange(Collections.<WeakReference<SourceListener>>emptySet(),
                cmpDef, SourceListener.SourceMonitorEvent.CHANGED, null);

        assertFalse("ComponentDef not cleared from cache", isInDefsCache(defs.get(DefType.COMPONENT), mdr));
        assertTrue("ControllerDef in same bundle as cmp should not be cleared from cache",
                isInDefsCache(defs.get(DefType.CONTROLLER), mdr));
    }

    /**
     * Add one of each following def to cache: a component, controller, renderer and application
     *
     * @return List of DefDescriptors that have been added to caches.
     */
    private Map<DefType, DefDescriptor<?>> addDefsToCaches() throws Exception {
        DefDescriptor<ComponentDef> cmpDef = definitionService.getDefDescriptor("test:test_button",
                ComponentDef.class);
        DefDescriptor<ControllerDef> cmpControllerDef = definitionService.getDefDescriptor(
                "js://test.test_button", ControllerDef.class);
        DefDescriptor<RendererDef> otherNamespaceDef = definitionService.getDefDescriptor(
                "js://gvpTest.labelProvider", RendererDef.class);
        DefDescriptor<ApplicationDef> appDef = definitionService.getDefDescriptor("test:basicCspTest", ApplicationDef.class);


        Map<DefType, DefDescriptor<?>> map = new HashMap<>();
        map.put(DefType.COMPONENT, cmpDef);
        map.put(DefType.CONTROLLER, cmpControllerDef);
        map.put(DefType.RENDERER, otherNamespaceDef);
        map.put(DefType.APPLICATION, appDef);

        for (DefType defType : map.keySet()) {
            DefDescriptor<?> dd = map.get(defType);
            definitionService.getDefinition(dd);
        }

        return map;
    }

    private boolean isInDescriptorFilterCache(DescriptorFilter filter, Set<DefDescriptor<?>> results,
            MasterDefRegistryImpl mdr) throws Exception {
        // taking the long road in determining what is in the cache because the current key implementation for
        // the descriptor cache is difficult to recreate.
        Cache<String, Set<DefDescriptor<?>>> cache = AuraPrivateAccessor.get(mdr, "descriptorFilterCache");
        for (String key : cache.getKeySet()) {
            if (key.startsWith(filter.toString() + "|")) {
                return results.equals(cache.getIfPresent(key));
            }
        }
        return false;
    }

    /**
     * Check to ensure that a DescriptorFilter is not in the cache.
     */
    private boolean notInDescriptorFilterCache(DescriptorFilter filter, MasterDefRegistryImpl mdr) throws Exception {
        Cache<String, Set<DefDescriptor<?>>> cache = AuraPrivateAccessor.get(mdr, "descriptorFilterCache");
        for (String key : cache.getKeySet()) {
            if (key.startsWith(filter.toString() + "|")) {
                return false;
            }
        }
        return true;
    }

    private boolean isInDefsCache(DefDescriptor<?> dd, MasterDefRegistryImpl mdr) throws Exception {
        Cache<DefDescriptor<?>, Optional<? extends Definition>> cache = AuraPrivateAccessor.get(mdr, "defsCache");
        return null != cache.getIfPresent(dd);
    }

    private boolean isInExistsCache(DefDescriptor<?> dd, MasterDefRegistryImpl mdr) throws Exception {
        Cache<DefDescriptor<?>, Boolean> cache = AuraPrivateAccessor.get(mdr, "existsCache");
        return Boolean.TRUE == cache.getIfPresent(dd);
    }
    
    private <D extends Definition> void callAssertAccess(MasterDefRegistryImpl mdr,
            DefDescriptor<?> referencingDescriptor, D def, Cache<String, String> accessCheckCache)
            throws Exception {
        Method assertAccess = mdr.getClass().getMethod("assertAccess", DefDescriptor.class,
                Definition.class, Cache.class);
        assertAccess.setAccessible(true);
        assertAccess.invoke(mdr, referencingDescriptor, def, accessCheckCache);
    }

    /**
     * Verify basic functionality of MasterDefRegistryImpl.getClientLibraries. The same methods are test in
     * ClientLibraryServiceImplTest, where we use ClientLibraryService.getUrls()
     *
     * @throws Exception
     */
    @Test
    public void testGetClientLibraries() throws Exception {
        MasterDefRegistry mdr = getAuraMDR();
        List<ClientLibraryDef> libDefs = mdr.getClientLibraries(null);
        assertNull(libDefs);

        DefDescriptor<ApplicationDef> appDesc = definitionService.getDefDescriptor(
                "clientLibraryTest:testDependencies", ApplicationDef.class);
        AuraContext cntx = contextService.getCurrentContext();
        cntx.setApplicationDescriptor(appDesc);
        definitionService.updateLoaded(appDesc);

        libDefs = mdr.getClientLibraries(cntx.getUid(appDesc));

        // 1 from clientLibraryTest:testDependencies
        // Update this number when you add new aura:clientLibrary tags to these components
        assertEquals(7, libDefs.size());
    }

    @Test
    public void testAssertAccess_IfGlobalAccessThenPassesCheck() throws Exception {
        when(globalDef.getAccess()).thenReturn(defAccess);
        when(defAccess.isGlobal()).thenReturn(true);
        MasterDefRegistry mdr = getAuraMDR();
        mdr.assertAccess(null, globalDef);

        verify(globalDef).getAccess();
        verify(defAccess).isGlobal();
    }

    @Test
    public void testAssertAccess_IfReferencedByUnsecuredPrefixThenPassesCheck() throws Exception {
        when(globalDef.getAccess()).thenReturn(defAccess);
        when(defAccess.isGlobal()).thenReturn(false);
        when(defAccess.requiresAuthentication()).thenReturn(true);
        when(referencingDesc.getPrefix()).thenReturn("aura");
        MasterDefRegistry mdr = getAuraMDR();
        mdr.assertAccess(referencingDesc, globalDef);

        verify(referencingDesc).getPrefix();
    }

    /**
     * Verify that if access cache has a reason to block access, then MDR throws NoAccessException.
     *
     * @throws Exception
     */
    @Test
    public void testAssertAccess_UsesCachedValueIfPresent_BlockAccess() throws Exception {
        DefDescriptor<ComponentDef> desc = addSourceAutoCleanup(ComponentDef.class,
                String.format(baseComponentTag, "", ""));
        when(mockAccessCheckCache.getIfPresent(anyString())).thenReturn("Error");
        MasterDefRegistryImpl mdr = (MasterDefRegistryImpl) getAuraMDR();
        Throwable ex = null;
        try {
            callAssertAccess(mdr, null, definitionService.getDefinition(desc), mockAccessCheckCache);
        } catch (InvocationTargetException ite) {
            ex = ite.getTargetException();
        } catch (Exception e) {
            ex = e;
        }
        if (ex == null) {
            fail("Expected NoAccessException because accessCache has reason to block def");
        }
        this.assertExceptionMessageStartsWith(ex, NoAccessException.class, "Error");
        verify(mockAccessCheckCache).getIfPresent(anyString());
    }

    /**
     * Verify that if access cache doesn't have any message to block access, then access checks passes through.
     *
     * @throws Exception
     */
    @Test
    public void testAssertAccess_UsesCachedValueIfPresent_AllowAccess() throws Exception {
        DefDescriptor<ComponentDef> desc = addSourceAutoCleanup(ComponentDef.class,
                String.format(baseComponentTag, "", ""));
        when(mockAccessCheckCache.getIfPresent(anyString())).thenReturn("");
        MasterDefRegistryImpl mdr = (MasterDefRegistryImpl) getAuraMDR();
        callAssertAccess(mdr, null, definitionService.getDefinition(desc), mockAccessCheckCache);

        verify(mockAccessCheckCache).getIfPresent(anyString());
    }

    @UnAdaptableTest("this pass when you run it as unit test in Eclipse. but fail in core autobuild : W-2967041")
    @Test
    public void testAssertAccess_StoreAccessInfoInCacheIfNotPresent() throws Exception {
        DefDescriptor<ComponentDef> desc = getAuraTestingUtil().addSourceAutoCleanup(ComponentDef.class,
                String.format(baseComponentTag, "", ""), StringSourceLoader.DEFAULT_CUSTOM_NAMESPACE + ":testComp",
                        NamespaceAccess.CUSTOM);

        when(mockAccessCheckCache.getIfPresent(anyString())).thenReturn(null);

        MasterDefRegistryImpl mdr = (MasterDefRegistryImpl) getAuraMDR();
        callAssertAccess(mdr, desc, definitionService.getDefinition(desc), mockAccessCheckCache);

        verify(mockAccessCheckCache).put(anyString(), anyString());

        callAssertAccess(mdr, desc, definitionService.getDefinition(desc), mockAccessCheckCache);
        verify(mockAccessCheckCache, times(2)).getIfPresent(anyString());
    }

    @Test
    public void testExistsCache() throws Exception {
    	//add couple def to caches
        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches();
        Map<DefType, DefDescriptor<?>> nonPrivDefs = addNonInternalDefsToCache();
        //now get two of them out
        DefDescriptor<?> rendererDef = defs.get(DefType.RENDERER);
        DefDescriptor<?> npRendererDef = nonPrivDefs.get(DefType.RENDERER);
        //make sure they are store as localDef in current context (we do that during getDef-->finishValidation
        AuraContext currentContext = contextService.getCurrentContext();
        assertTrue("rendererDef should be in current context's localDef", currentContext.hasLocalDef(rendererDef) );
        assertTrue("npRendererDef should be in current context's localDef", currentContext.hasLocalDef(npRendererDef) );
        //they do exist in currentContex's localDef
        assertTrue("rendererDef should exist", rendererDef.exists());
        assertTrue("npRendererDef should exist", npRendererDef.exists());
        //note calling exist like above won't put these def in existCache, as they already exist in currentContext's localDef
        MasterDefRegistry mdr = getAuraMDR();
        assertFalse("npRendererDef is in current context's localDef already, calling exist() won't add it to exist cache", 
        		isInExistsCache(rendererDef, (MasterDefRegistryImpl) mdr));
        assertFalse("npRendererDef is in current context's localDef already, calling exist() won't add it to exist cache", 
        		isInExistsCache(npRendererDef, (MasterDefRegistryImpl) mdr));

        //now restart the context with a new MDR
        MasterDefRegistry mdr2 = restartContextGetNewMDR();
        //get currentContext again, note defs are not in the new context's localDef
        currentContext = contextService.getCurrentContext();
        assertFalse("rendererDef should not be in current context's localDef", currentContext.hasLocalDef(rendererDef) );
        assertFalse("npRendererDef should not be in current context's localDef", currentContext.hasLocalDef(npRendererDef) );
        
        assertFalse("npRendererDef should not be in exist cache yet", isInExistsCache(rendererDef, (MasterDefRegistryImpl) mdr2));
        assertFalse("npRendererDef should not be in exist cache", isInExistsCache(npRendererDef, (MasterDefRegistryImpl) mdr2));
 
        assertFalse("rendererDef should be in current context's localDef", currentContext.hasLocalDef(rendererDef) );
        assertFalse("npRendererDef should be in current context's localDef", currentContext.hasLocalDef(npRendererDef) );
        //calling exist now will touch existCache, if the def is in defsCache, it will store as TRUE, if not, FALSE
        rendererDef.exists();
        npRendererDef.exists();
        //make sure defs are still not in currentContext's localDef
        assertFalse("rendererDef should not be in current context's localDef", currentContext.hasLocalDef(rendererDef) );
        assertFalse("npRendererDef should not be in current context's localDef", currentContext.hasLocalDef(npRendererDef) );
        
        assertTrue("rendererDef should be in existCache", 
        		this.isInExistsCache(rendererDef, (MasterDefRegistryImpl) mdr2));
        //non-system namespace is not in defsCache, hence store as FALSE in existCache
        assertFalse("npRendererDef should store as FALSE in existCache", 
        		this.isInExistsCache(npRendererDef, (MasterDefRegistryImpl) mdr2));
    }

    @UnAdaptableTest("namespace start with 'c' means something special in core")
    @Test
    public void testDefsCache() throws Exception {
        //add couple defs
        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches();
        Map<DefType, DefDescriptor<?>> nonPrivDefs = addNonInternalDefsToCache();

        DefDescriptor<?> rendererDef = defs.get(DefType.RENDERER);
        DefDescriptor<?> npRendererDef = nonPrivDefs.get(DefType.RENDERER);

        MasterDefRegistry mdr = getAuraMDR();
        assertTrue("rendererDef should be in defsCache", isInDefsCache(rendererDef, (MasterDefRegistryImpl) mdr));
        //we don't put non-system namespace in defsCache
        assertFalse("npRendererDef should not be in defsCache", isInDefsCache(npRendererDef, (MasterDefRegistryImpl) mdr));

        MasterDefRegistry mdr2 = restartContextGetNewMDR();
        assertTrue("rendererDef should be in defsCache", isInDefsCache(rendererDef, (MasterDefRegistryImpl) mdr2));
        assertFalse("npRendererDef shouldn't be in defsCache", isInDefsCache(npRendererDef, (MasterDefRegistryImpl) mdr2));
    }

    @SuppressWarnings("unused")
	@UnAdaptableTest("namesapce start with c means something special in core")
    @Test
    public void testDescriptorFilterCache() throws Exception {
        MasterDefRegistry mdr = getAuraMDR();
        MasterDefRegistryImpl mdri = (MasterDefRegistryImpl) mdr;
        //now add couple defs to caches
        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches();
        Map<DefType, DefDescriptor<?>> nonPrivDefs = addNonInternalDefsToCache();
        //internal namespace
        DescriptorFilter filter = new DescriptorFilter("markup://test:test_button");
        Set<DefDescriptor<?>> results = mdr.find(filter);
        assertTrue("markup://test:test_button should be cached", isInDescriptorFilterCache(filter, results, mdri));
        filter = new DescriptorFilter("js://test:test_button");
        results = mdr.find(filter);
        assertTrue("js://test:test_button should be cached", isInDescriptorFilterCache(filter, results, mdri));
        //internal namespace with wild-card matcher, notice we do cache namespace:*
        filter = new DescriptorFilter("markup://test:*");
        results = mdr.find(filter);
        assertTrue("markup://test:* should be cached", isInDescriptorFilterCache(filter, results, mdri));
        filter = new DescriptorFilter("markup://*:test_button");
        results = mdr.find(filter);
        assertFalse("markup://*:test_button should not be cached", isInDescriptorFilterCache(filter, results, mdri));
        /*filter = new DescriptorFilter("*://test:test_button");
        results = mdr.find(filter);
        assertFalse("*://test:test_button should not be cached", isInDescriptorFilterCache(filter, results, mdri));
        */
        //custom namespace
        filter = new DescriptorFilter("js://cstring1:labelProvider");
        results = mdr.find(filter);
        assertFalse("js://cstring1:labelProvider shouldn't be cached", isInDescriptorFilterCache(filter, results, mdri));
        //custom namespace with wild-card matcher
        filter = new DescriptorFilter("js://cstring1:*");
        results = mdr.find(filter);
        assertFalse("js://cstring1:* shouldn't be cached", isInDescriptorFilterCache(filter, results, mdri));
        filter = new DescriptorFilter("js://*:*");
        results = mdr.find(filter);
        assertFalse("js://*:* shouldn't be cached", isInDescriptorFilterCache(filter, results, mdri));
        filter = new DescriptorFilter("*://cstring1:*");
        results = mdr.find(filter);
        assertFalse("*://*:* shouldn't be cached", isInDescriptorFilterCache(filter, results, mdri));
        
        //MAGIC: apex prefix, see cacheDependencyExceptions in ConfigAdapterImpl.java
        filter = new DescriptorFilter("apex://applauncher:appmenu");
        results = mdr.find(filter);
        assertFalse("apex://applauncher:appmenu shouldn't be cached", isInDescriptorFilterCache(filter, results, mdri));
        //apex prefix with wild-card matcher
        filter = new DescriptorFilter("apex://applauncher:*");
        results = mdr.find(filter);
        assertFalse("apex://applauncher:* shouldn't be cached", isInDescriptorFilterCache(filter, results, mdri));
        filter = new DescriptorFilter("apex://*:*");
        results = mdr.find(filter);
        assertFalse("apex://*:* shouldn't be cached", isInDescriptorFilterCache(filter, results, mdri));
       
    }

    /**
     * Test a constant Descriptor filter.
     *
     * Preconditions: (1) Cacheable registry. (2) DescriptorFilter.isConstant() should be true.
     *
     * PostConditions: (1) registry.find() should _not_ be called. (2) We should get exactly one descriptor. (3) It
     * should contain the def descriptor. (4) There should be no cache.
     */
    @SuppressWarnings("unchecked")
    public <D extends Definition> void testDescriptorFilterConstant() throws Exception {
        DefRegistry<D> mockedRegistry = Mockito.mock(DefRegistry.class);
        DefDescriptor<D> dd = (DefDescriptor<D>) definitionService.getDefDescriptor("markup://aura:mocked", ComponentDef.class);
        Set<DefDescriptor<?>> findable = Sets.newHashSet();
        findable.add(dd);

        Mockito.when(mockedRegistry.getNamespaces()).thenReturn(Sets.newHashSet("aura"));
        Mockito.when(mockedRegistry.getPrefixes()).thenReturn(Sets.newHashSet("markup"));
        Mockito.when(mockedRegistry.getDefTypes()).thenReturn(Sets.newHashSet(DefType.COMPONENT));
        Mockito.when(mockedRegistry.find((DescriptorFilter) Mockito.any())).thenReturn(findable);
        Mockito.when(mockedRegistry.hasFind()).thenReturn(true);
        Mockito.when(mockedRegistry.exists(dd)).thenReturn(true);
        MasterDefRegistryImpl mdr = new MasterDefRegistryImpl(configAdapter, definitionService, loggingService,
                cachingService, mockedRegistry);

        DescriptorFilter df = new DescriptorFilter("aura:mocked", DefType.COMPONENT);
        Set<DefDescriptor<?>> result = mdr.find(df);
        assertEquals("Resulting set should contain exactly 1 item", 1, result.size());
        assertTrue("Resulting set should contain our descriptor", result.contains(dd));
        Mockito.verify(mockedRegistry, Mockito.never()).find((DescriptorFilter) Mockito.any());
        assertTrue("No caching for constant filters", notInDescriptorFilterCache(df, mdr));
    }

    /**
     * Test a wildcard name Descriptor filter (cacheable).
     *
     * Preconditions: (1) Cacheable registry. (2) DescriptorFilter.isConstant() should be true.
     *
     * PostConditions: (1) registry.find() should be called. (2) We should get exactly one descriptor. (3) It should
     * contain the def descriptor. (4) There should be a cache.
     */
    @Test
    public void testDescriptorFilterNameWildcardWithCache() throws Exception {
        DefRegistry<?> mockedRegistry = Mockito.mock(DefRegistry.class);
        DefDescriptor<?> dd = definitionService.getDefDescriptor("markup://aura:mocked", ComponentDef.class);
        Set<DefDescriptor<?>> findable = Sets.newHashSet();
        AuraContext mockedContext = Mockito.mock(AuraContext.class);
        findable.add(dd);

        Mockito.when(mockedRegistry.getNamespaces()).thenReturn(Sets.newHashSet("aura"));
        Mockito.when(mockedRegistry.getPrefixes()).thenReturn(Sets.newHashSet("markup"));
        Mockito.when(mockedRegistry.getDefTypes()).thenReturn(Sets.newHashSet(DefType.COMPONENT));
        Mockito.when(mockedRegistry.find((DescriptorFilter) Mockito.any())).thenReturn(findable);
        Mockito.when(mockedRegistry.hasFind()).thenReturn(true);
        Mockito.when(mockedRegistry.isCacheable()).thenReturn(true);

        MasterDefRegistryImpl mdr = new MasterDefRegistryImpl(configAdapter, definitionService, loggingService,
                cachingService, mockedRegistry);
        mdr.setContext(mockedContext);

        DescriptorFilter df = new DescriptorFilter("aura:*", DefType.COMPONENT);
        Set<DefDescriptor<?>> result = mdr.find(df);
        assertEquals("Resulting set should contain exactly 1 item", 1, result.size());
        assertTrue("Resulting set should contain our descriptor", result.contains(dd));
        Mockito.verify(mockedRegistry).find(Mockito.eq(df));
        assertTrue("Caching for wildcard names", isInDescriptorFilterCache(df, findable, mdr));
    }

    /**
     * Test a wildcard name Descriptor filter (not cacheable).
     *
     * Preconditions: (1) Non-Cacheable registry. (2) DescriptorFilter.isConstant() should be true.
     *
     * PostConditions: (1) registry.find() should be called. (2) We should get exactly one descriptor. (3) It should
     * contain the def descriptor. (4) There should be no cache.
     */
    @Test
    public void testDescriptorFilterNameWildcardWithoutCache() throws Exception {
        DefRegistry<?> mockedRegistry = Mockito.mock(DefRegistry.class);
        DefDescriptor<?> dd = definitionService.getDefDescriptor("markup://aura:mocked", ComponentDef.class);
        Set<DefDescriptor<?>> findable = Sets.newHashSet();
        AuraContext mockedContext = Mockito.mock(AuraContext.class);
        findable.add(dd);

        Mockito.when(mockedRegistry.getNamespaces()).thenReturn(Sets.newHashSet("aura"));
        Mockito.when(mockedRegistry.getPrefixes()).thenReturn(Sets.newHashSet("markup"));
        Mockito.when(mockedRegistry.getDefTypes()).thenReturn(Sets.newHashSet(DefType.COMPONENT));
        Mockito.when(mockedRegistry.find((DescriptorFilter) Mockito.any())).thenReturn(findable);
        Mockito.when(mockedRegistry.hasFind()).thenReturn(true);
        Mockito.when(mockedRegistry.isCacheable()).thenReturn(false);

        MasterDefRegistryImpl mdr = new MasterDefRegistryImpl(configAdapter, definitionService, loggingService,
                cachingService, mockedRegistry);
        mdr.setContext(mockedContext);

        DescriptorFilter df = new DescriptorFilter("aura:*", DefType.COMPONENT);
        Set<DefDescriptor<?>> result = mdr.find(df);
        assertEquals("Resulting set should contain exactly 1 item", 1, result.size());
        assertTrue("Resulting set should contain our descriptor", result.contains(dd));
        Mockito.verify(mockedRegistry).find(Mockito.eq(df));
        assertTrue("Caching for wildcard names", notInDescriptorFilterCache(df, mdr));
    }

    /**
     * Test a wildcard namespace Descriptor filter (cacheable).
     *
     * Preconditions: (1) Cacheable registry. (2) DescriptorFilter.isConstant() should be true.
     *
     * PostConditions: (1) registry.find() should be called. (2) We should get exactly one descriptor. (3) It should
     * contain the def descriptor. (4) There should be no cache.
     */
    @Test
    public void testDescriptorFilterNamespaceWildcard() throws Exception {
        DefRegistry<?> mockedRegistry = Mockito.mock(DefRegistry.class);
        DefDescriptor<?> dd = definitionService.getDefDescriptor("markup://aura:mocked", ComponentDef.class);
        Set<DefDescriptor<?>> findable = Sets.newHashSet();
        AuraContext mockedContext = Mockito.mock(AuraContext.class);
        findable.add(dd);

        Mockito.when(mockedRegistry.getNamespaces()).thenReturn(Sets.newHashSet("aura"));
        Mockito.when(mockedRegistry.getPrefixes()).thenReturn(Sets.newHashSet("markup"));
        Mockito.when(mockedRegistry.getDefTypes()).thenReturn(Sets.newHashSet(DefType.COMPONENT));
        Mockito.when(mockedRegistry.find((DescriptorFilter) Mockito.any())).thenReturn(findable);
        Mockito.when(mockedRegistry.hasFind()).thenReturn(true);
        Mockito.when(mockedRegistry.isCacheable()).thenReturn(true);

        MasterDefRegistryImpl mdr = new MasterDefRegistryImpl(configAdapter, definitionService, loggingService,
                cachingService, mockedRegistry);
        mdr.setContext(mockedContext);

        DescriptorFilter df = new DescriptorFilter("*:mocked", DefType.COMPONENT);
        Set<DefDescriptor<?>> result = mdr.find(df);
        assertEquals("Resulting set should contain exactly 1 item", 1, result.size());
        assertTrue("Resulting set should contain our descriptor", result.contains(dd));
        Mockito.verify(mockedRegistry).find(Mockito.eq(df));
        assertTrue("No caching for wildcard namespaces", notInDescriptorFilterCache(df, mdr));
    }
    

    private MasterDefRegistry restartContextGetNewMDR() {
        // simulate new request
        MasterDefRegistry mdr = getAuraMDR();
        AuraContext ctx = contextService.getCurrentContext();
        Mode mode = ctx.getMode();
        Format format = ctx.getFormat();
        Authentication access = ctx.getAccess();
        contextService.endContext();

        contextService.startContext(mode, format, access);
        MasterDefRegistry mdr2 = getAuraMDR();
        assertFalse("MasterDefRegistry should be different after restart of context", mdr == mdr2);
        return mdr2;
    }

    /**
     * add one of each to cache: component, controller, renderer, application. all in custom namespace
     *
     * @return List of DefDescriptors that have been added to the caches.
     */
    @UnAdaptableTest("namesapce start with c means something special in core")
    private Map<DefType, DefDescriptor<?>> addNonInternalDefsToCache() throws Exception {
        DefDescriptor<ComponentDef> cmpDef = getAuraTestingUtil().addSourceAutoCleanup(ComponentDef.class,
                "<aura:component>"
                        + "<aura:attribute name='label' type='String'/>"
                        + "<aura:attribute name='class' type='String'/>"
                        + "<aura:registerevent name='press' type='test:test_press'/>"
                        + "<div onclick='{!c.press}' class='{!v.class}'>{!v.label}</div>"
                        + "</aura:component>", "cstring:test_button",
                        NamespaceAccess.CUSTOM);
        DefDescriptor<ControllerDef> cmpControllerDef = getAuraTestingUtil().addSourceAutoCleanup(ControllerDef.class,
                "{    press : function(cmp, event){        cmp.getEvent('press').fire();    }}",
                "cstring.test_button",
                        NamespaceAccess.CUSTOM);
        DefDescriptor<RendererDef> otherNamespaceDef = getAuraTestingUtil()
                .addSourceAutoCleanup(
                        RendererDef.class,
                        "({render: function(cmp) {\n"
                                + "cmp.getValue('v.simplevalue1').setValue($A.get('$Label' + '.Related_Lists' + '.task_mode_today', cmp));\n"
                                + "cmp.getValue('v.simplevalue2').setValue($A.get('$Label.DOESNT.EXIST', cmp));\n"
                                + "cmp.getValue('v.simplevalue3').setValue($A.get('$Label.Related_Lists.DOESNTEXIST', cmp));\n"
                                + "// Both section and name are required. This request will return undefined and no action is requested.\n"
                                + "cmp.getValue('v.simplevalue4').setValue($A.get('$Label.DOESNTEXIST', cmp));\n"
                                + "// These requests are here to test that there are no multiple action requests for the same $Label\n"
                                + "// See LabelValueProviderUITest.java\n"
                                + "var tmt = $A.get('$Label.Related_Lists.task_mode_today', cmp);\n"
                                + "tmt = $A.get('$Label.Related_Lists.task_mode_today', cmp);\n"
                                + "tmt = $A.get('$Label.Related_Lists.task_mode_today', cmp);\n"
                                + "tmt = $A.get('$Label.Related_Lists.task_mode_today', cmp);\n"
                                + "tmt = $A.get('$Label.Related_Lists.task_mode_today', cmp);\n"
                                + "return this.superRender();\n"
                                + "}})",
                        "cstring1.labelProvider",
                        NamespaceAccess.CUSTOM);
        DefDescriptor<ApplicationDef> app = getAuraTestingUtil().addSourceAutoCleanup(
                ApplicationDef.class,
                "<aura:application></aura:application>", "cstring1:blank",
                        NamespaceAccess.CUSTOM);


        Map<DefType, DefDescriptor<?>> map = new HashMap<>();
        map.put(DefType.COMPONENT, cmpDef);
        map.put(DefType.CONTROLLER, cmpControllerDef);
        map.put(DefType.RENDERER, otherNamespaceDef);
        map.put(DefType.APPLICATION, app);

        for (DefType defType : map.keySet()) {
            DefDescriptor<?> dd = map.get(defType);
            definitionService.getDefinition(dd);
        }

        return map;
    }

    @Test
    public void testGetDefNotValidateJsCodeForCustomComponent() throws Exception {
        String cmpName = StringSourceLoader.DEFAULT_CUSTOM_NAMESPACE+":testNotValidateCustomComponent";
        DefDescriptor<ComponentDef> cmpDesc = getAuraTestingUtil().addSourceAutoCleanup(
                ComponentDef.class, "<aura:component></aura:component>", cmpName, NamespaceAccess.CUSTOM);
        DefDescriptor<ControllerDef> controllerDesc = definitionService.getDefDescriptor(cmpDesc,
                DefDescriptor.JAVASCRIPT_PREFIX, ControllerDef.class);;

        String controllerCode = "({ function1: function(cmp) {var a = {k:}} })";
        getAuraTestingUtil().addSourceAutoCleanup(controllerDesc, controllerCode, NamespaceAccess.CUSTOM);

        MasterDefRegistry mdr = contextService.getCurrentContext().getDefRegistry();
        ComponentDef cmpDef = mdr.getDef(cmpDesc);

        assertEquals(cmpDesc.getQualifiedName(), cmpDef.getDescriptor().getQualifiedName());
    }

    @Test
    public void testGetDefValidateJsCodeForCacheableComponent() throws Exception {
        // Components under internal namespace are cachable
        String cmpName = StringSourceLoader.DEFAULT_NAMESPACE+":testGetDefValidateJsCodeForCacheableComponent";
        DefDescriptor<ComponentDef> cmpDesc = getAuraTestingUtil().addSourceAutoCleanup(
                ComponentDef.class, "<aura:component></aura:component>", cmpName, NamespaceAccess.INTERNAL);
        DefDescriptor<ControllerDef> controllerDesc = definitionService.getDefDescriptor(cmpDesc,
                DefDescriptor.JAVASCRIPT_PREFIX, ControllerDef.class);;

        String controllerCode = "({ function1: function(cmp) {var a = {k:}} })";
        getAuraTestingUtil().addSourceAutoCleanup(controllerDesc, controllerCode, NamespaceAccess.INTERNAL);

        MasterDefRegistry mdr = contextService.getCurrentContext().getDefRegistry();
        try {
            mdr.getDef(cmpDesc);
            fail("Expecting an InvalidDefinitionException");
        } catch(Exception e) {
            String expectedMsg = String.format("JS Processing Error: %s", cmpDesc.getQualifiedName());
            this.assertExceptionMessageContains(e, InvalidDefinitionException.class, expectedMsg);
        }
    }

    private MasterDefRegistry getAuraMDR() {
        return contextService.getCurrentContext().getDefRegistry();
    }

    /**
     * A fake type def, this could probably be a mock, the tricky part being isValid().
     */
    @SuppressWarnings("serial")
    private static class FakeTypeDef implements TypeDef {
        private boolean valid;
        DefDescriptor<TypeDef> desc;

        public FakeTypeDef(DefDescriptor<TypeDef> desc) {
            this.desc = desc;
        }

        @Override
        public void validateDefinition() throws QuickFixException {
        }

        @Override
        public void appendDependencies(Set<DefDescriptor<?>> dependencies) {
        }

        @Override
        public void validateReferences() throws QuickFixException {
        }

        @Override
        public void markValid() {
            valid = true;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public String getName() {
            return "name";
        }

        @Override
        public Location getLocation() {
            return null;
        }

        @Override
        public DefinitionAccess getAccess() {
            return null;
        }

        @Override
        public <D extends Definition> D getSubDefinition(SubDefDescriptor<D, ?> descriptor) {
            return null;
        }

        @Override
        public void retrieveLabels() throws QuickFixException {
        }

        @Override
        public String getAPIVersion() {
            return null;
        }

        @Override
        public String getDescription() {
            return "description";
        }

        @Override
        public String getOwnHash() {
            return "aaaa";
        }

        @Override
        public void appendSupers(Set<DefDescriptor<?>> supers) throws QuickFixException {
        }

        @Override
        public void serialize(Json json) throws IOException {

        }

        @Override
        public DefDescriptor<TypeDef> getDescriptor() {
            return desc;
        }

        @Override
        public Object valueOf(Object stringRep) {
            return null;
        }

        @Override
        public Object wrap(Object o) {
            return null;
        }

        @Override
        public Object getExternalType(String prefix) throws QuickFixException {
            return null;
        }

        @Override
        public Object initialize(Object config, BaseComponent<?, ?> valueProvider) throws QuickFixException {
            return null;
        }

        @Override
        public void appendDependencies(Object instance, Set<DefDescriptor<?>> deps) {
        }
    }

    /**
     * A fake registry to check locking as we call.
     */
    @SuppressWarnings("serial")
    private static class FakeRegistry implements DefRegistry<TypeDef> {
        public DefDescriptor<TypeDef> desc;
        public TypeDef def;
        private final Lock rLock;

        public FakeRegistry(Lock rLock, Lock wLock, DefinitionService definitionService) {
            this.desc = definitionService.getDefDescriptor("java://fake.type", TypeDef.class);
            this.def = new FakeTypeDef(desc);
            this.rLock = rLock;
        }

        @Override
        public TypeDef getDef(DefDescriptor<TypeDef> descriptor) throws QuickFixException {
            Mockito.verify(rLock, Mockito.times(1)).lock();
            Mockito.verify(rLock, Mockito.never()).unlock();
            if (descriptor.equals(desc)) {
                return def;
            }
            return null;
        }

        @Override
        public boolean hasFind() {
            return true;
        }

        @Override
        public Set<DefDescriptor<?>> find(DescriptorFilter matcher) {
            Mockito.verify(rLock, Mockito.times(1)).lock();
            Mockito.verify(rLock, Mockito.never()).unlock();
            Set<DefDescriptor<?>> found = Sets.newHashSet();
            found.add(desc);
            return found;
        }

        @Override
        public boolean exists(DefDescriptor<TypeDef> descriptor) {
            Mockito.verify(rLock, Mockito.times(1)).lock();
            Mockito.verify(rLock, Mockito.never()).unlock();
            return desc.equals(descriptor);
        }

        @Override
        public Set<DefType> getDefTypes() {
            Set<DefType> types = Sets.newHashSet();
            types.add(DefType.TYPE);
            return types;
        }

        @Override
        public Set<String> getPrefixes() {
            Set<String> prefixes = Sets.newHashSet();
            prefixes.add("java");
            return prefixes;
        }

        @Override
        public Set<String> getNamespaces() {
            Set<String> prefixes = Sets.newHashSet();
            prefixes.add("fake");
            return prefixes;
        }

        @Override
        public Source<TypeDef> getSource(DefDescriptor<TypeDef> descriptor) {
            return null;
        }

        @Override
        public boolean isCacheable() {
            return true;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public void reset() {
    }
    }

    /**
     * A private class to hold all the info for a lock test.
     *
     * This sets up the mocks so that we can test locking, if it is instantiated, you _must_ call clear() in a finally
     * block. The locking is not real here, so have a care.
     */
    private static class LockTestInfo {
        public final MasterDefRegistryImpl mdr;
        public final FakeRegistry reg;
        public final Lock rLock;
        public final Lock wLock;

        public LockTestInfo(ConfigAdapter configAdapter, DefinitionService definitionService, LoggingService loggingService,
                            CachingService cachingService) {
            //ServiceLoader sl = ServiceLocatorMocker.spyOnServiceLocator();
            this.rLock = Mockito.mock(Lock.class, "rLock");
            this.wLock = Mockito.mock(Lock.class, "wLock");
            //CachingService acs = Mockito.spy(sl.get(CachingService.class));
            //Mockito.stub(sl.get(CachingService.class)).toReturn(acs);
            //Mockito.stub(acs.getReadLock()).toReturn(rLock);
            //Mockito.stub(acs.getWriteLock()).toReturn(wLock);
            this.reg = new FakeRegistry(rLock, wLock, definitionService);
            this.mdr = new MasterDefRegistryImpl(configAdapter, definitionService, loggingService,
                    cachingService, reg);
        }

        public void clear() {
            //ServiceLocatorMocker.unmockServiceLocator();
        }
    }

    /**
     * Test getDef to ensure locking is minimized.
     *
     * This asserts that within an MDR we only lock once for any number of getDef calls for a single def.
     *
     * FIXME: this should not hit the caching service.
     */
    public void _testGetDefLocking() throws Exception {
        LockTestInfo lti = null;

        lti = new LockTestInfo(configAdapter, definitionService, loggingService, cachingService);
        try {
            Definition d = lti.mdr.getDef(lti.reg.desc);
            Mockito.verify(lti.rLock, Mockito.times(1)).lock();
            Mockito.verify(lti.rLock, Mockito.times(1)).unlock();
            assertEquals(d, lti.mdr.getDef(lti.reg.desc));
            Mockito.verify(lti.rLock, Mockito.times(1)).lock();
            Mockito.verify(lti.rLock, Mockito.times(1)).unlock();
            Mockito.verify(lti.wLock, Mockito.never()).lock();
            Mockito.verify(lti.wLock, Mockito.never()).unlock();
        } finally {
            lti.clear();
        }
    }

    /**
     * Test find(matcher) to ensure locking is minimized.
     *
     * This asserts that within an MDR we only lock once for a call to find.
     */
    public void _testFindMatcherLocking() throws Exception {
        LockTestInfo lti = null;

        lti = new LockTestInfo(configAdapter, definitionService, loggingService, cachingService);
        try {
            lti.mdr.find(new DescriptorFilter("bah:hum*"));
            Mockito.verify(lti.rLock, Mockito.times(1)).lock();
            Mockito.verify(lti.rLock, Mockito.times(1)).unlock();
            lti.mdr.find(new DescriptorFilter("bah:hum*"));
            // we always lock, so we cannot check for a single lock here.
            Mockito.verify(lti.rLock, Mockito.times(2)).lock();
            Mockito.verify(lti.rLock, Mockito.times(2)).unlock();
            Mockito.verify(lti.wLock, Mockito.never()).lock();
            Mockito.verify(lti.wLock, Mockito.never()).unlock();
        } finally {
            lti.clear();
        }
    }

    /**
     * Test exists to ensure locking is minimized.
     *
     * This asserts that within an MDR we only lock once for any number of calls to exists.
     */
    public void _testExistsLocking() throws Exception {
        LockTestInfo lti = null;

        lti = new LockTestInfo(configAdapter, definitionService, loggingService, cachingService);
        try {
            lti.mdr.exists(lti.reg.desc);
            Mockito.verify(lti.rLock, Mockito.times(1)).lock();
            Mockito.verify(lti.rLock, Mockito.times(1)).unlock();
            lti.mdr.exists(lti.reg.desc);
            Mockito.verify(lti.rLock, Mockito.times(1)).lock();
            Mockito.verify(lti.rLock, Mockito.times(1)).unlock();
            Mockito.verify(lti.wLock, Mockito.never()).lock();
            Mockito.verify(lti.wLock, Mockito.never()).unlock();
        } finally {
            lti.clear();
        }
    }

    /**
     * Test getUid for locking.
     *
     * getUid always takes the lock, maybe we should avoid this?
     */
    public void _testGetUidLocking() throws Exception {
        LockTestInfo lti = null;

        lti = new LockTestInfo(configAdapter, definitionService, loggingService, cachingService);
        try {
            lti.mdr.getUid(null, lti.reg.desc);
            Mockito.verify(lti.rLock, Mockito.times(1)).lock();
            Mockito.verify(lti.rLock, Mockito.times(1)).unlock();
            lti.mdr.getUid(null, lti.reg.desc);
            // We lock every time here. Probably should not.
            Mockito.verify(lti.rLock, Mockito.times(2)).lock();
            Mockito.verify(lti.rLock, Mockito.times(2)).unlock();
            Mockito.verify(lti.wLock, Mockito.never()).lock();
            Mockito.verify(lti.wLock, Mockito.never()).unlock();
        } finally {
            lti.clear();
        }
    }

    private class MasterDefRegistryImplOverride extends MasterDefRegistryImpl {
        public MasterDefRegistryImplOverride(@Nonnull DefRegistry<?>... registries) {
            super(configAdapter, definitionService, loggingService, cachingService, registries);
        }

        @Override
        protected <T extends Definition> DependencyEntry compileDE(@Nonnull DefDescriptor<T> descriptor)
                throws QuickFixException {
            return super.compileDE(descriptor);
        }
    }
}
