//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.start;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Pattern;

/**
 * Access for all modules declared, as well as what is enabled.
 *
 * 多个模块
 */
public class Modules implements Iterable<Module> {

    /**
     * 基目录和家目录
     */
    private final BaseHome baseHome;

    /**
     * 启动参数
     */
    private final StartArgs args;

    /**
     * 模块列表
     */
    private Map<String, Module> modules = new HashMap<>();

    /**
     * modules that may appear in the resolved graph but are undefined in the module system
     * 
     * ex: modules/npn/npn-1.7.0_01.mod (property expansion resolves to non-existent file)
     *
     * 缺失的模块
     */
    private Set<String> missingModules = new HashSet<String>();

    /**
     * 最大深度
     */
    private int maxDepth = -1;

    /**
     * 构造方法
     *
     * @param basehome
     * @param args
     */
    public Modules(BaseHome basehome, StartArgs args) {
        this.baseHome = basehome;
        this.args = args;
    }

    /**
     * 获取模块的名称集合
     *
     * @param moduleSet
     * @return
     */
    private Set<String> asNameSet(Set<Module> moduleSet) {
        Set<String> ret = new HashSet<>();
        for (Module module : moduleSet) {
            ret.add(module.getName());
        }
        return ret;
    }

    /**
     * 确认无循环引用
     *
     * @param module
     * @param refs
     */
    private void assertNoCycle(Module module, Stack<String> refs) {
        for (Module parent : module.getParentEdges()) {
            if (refs.contains(parent.getName())) {
                // Cycle detected.
                StringBuilder err = new StringBuilder();
                err.append("A cyclic reference in the modules has been detected: ");
                for (int i = 0; i < refs.size(); i++) {
                    if (i > 0) {
                        err.append(" -> ");
                    }
                    err.append(refs.get(i));
                }
                err.append(" -> ").append(parent.getName());
                throw new IllegalStateException(err.toString());
            }

            refs.push(parent.getName());
            assertNoCycle(parent,refs);
            refs.pop();
        }
    }

    /**
     * 计算深度
     *
     * @param module
     * @param depthNow
     */
    private void bfsCalculateDepth(final Module module, final int depthNow) {
        int depth = depthNow + 1;

        // Set depth on every child first
        for (Module child : module.getChildEdges()) {
            child.setDepth(Math.max(depth,child.getDepth()));
            this.maxDepth = Math.max(this.maxDepth,child.getDepth());
        }

        // Dive down
        for (Module child : module.getChildEdges()) {
            bfsCalculateDepth(child,depth);
        }
    }

    /**
     * Using the provided dependencies, build the module graph
     *
     * 构建模块图
     */
    public void buildGraph() throws FileNotFoundException, IOException {
        normalizeDependencies();
        
        // Connect edges
        for (Module module : modules.values()) {
            for (String parentName : module.getParentNames()) {
                Module parent = get(parentName);

                if (parent == null) {
                    if (Props.hasPropertyKey(parentName)) {
                        StartLog.debug("Module property not expandable (yet) [%s]",parentName);
                    } else {
                        StartLog.warn("Module not found [%s]",parentName);
                    }
                } else {
                    module.addParentEdge(parent);
                    parent.addChildEdge(module);
                }
            }

            for (String optionalParentName : module.getOptionalParentNames()) {
                Module optional = get(optionalParentName);
                if (optional == null) {
                    StartLog.debug("Optional module not found [%s]",optionalParentName);
                } else if (optional.isEnabled()) {
                    module.addParentEdge(optional);
                    optional.addChildEdge(module);
                }
            }
        }

        // Verify there is no cyclic references
        // 确保没有循环引用
        Stack<String> refs = new Stack<>();
        for (Module module : modules.values()) {
            refs.push(module.getName());
            assertNoCycle(module,refs);
            refs.pop();
        }

        // Calculate depth of all modules for sorting later
        for (Module module : modules.values()) {
            if (module.getParentEdges().isEmpty()) {
                bfsCalculateDepth(module,0);
            }
        }
    }

    /**
     * 清理缺失的模块
     */
    public void clearMissing() {
        missingModules.clear();
    }

    /**
     * 获取模块数量
     *
     * @return
     */
    public Integer count() {
        return modules.size();
    }

    /**
     * 显示信息
     */
    public void dump() {
        // 默认按照名称进行排序
        List<Module> ordered = new ArrayList<>();
        ordered.addAll(modules.values());
        Collections.sort(ordered,new Module.NameComparator());

        List<Module> active = resolveEnabled();

        for (Module module : ordered) {
            boolean activated = active.contains(module);
            boolean enabled = module.isEnabled();
            boolean transitive = activated && !enabled;

            char status = '-';
            if (enabled) {
                status = '*';
            } else if (transitive) {
                status = '+';
            }

            System.out.printf("%n %s Module: %s%n",status,module.getName());
            if (!module.getName().equals(module.getFilesystemRef())) {
                System.out.printf("      Ref: %s%n",module.getFilesystemRef());
            }
            for (String parent : module.getParentNames()) {
                System.out.printf("   Depend: %s%n",parent);
            }
            for (String lib : module.getLibs()) {
                System.out.printf("      LIB: %s%n",lib);
            }
            for (String xml : module.getXmls()) {
                System.out.printf("      XML: %s%n",xml);
            }
            if (StartLog.isDebugEnabled()) {
                System.out.printf("    depth: %d%n",module.getDepth());
            }
            if (activated) {
                for (String source : module.getSources()) {
                    System.out.printf("  Enabled: <via> %s%n",source);
                }
                if (transitive) {
                    System.out.printf("  Enabled: <via transitive reference>%n");
                }
            } else {
                System.out.printf("  Enabled: <not enabled in this configuration>%n");
            }
        }
    }

    /**
     * 打印启动的模块树
     */
    public void dumpEnabledTree() {
        List<Module> ordered = new ArrayList<>();
        ordered.addAll(modules.values());
        Collections.sort(ordered,new Module.DepthComparator());

        List<Module> active = resolveEnabled();

        for (Module module : ordered) {
            if (active.contains(module)) {
                // Show module name
                String indent = toIndent(module.getDepth());
                System.out.printf("%s + Module: %s [%s]%n",indent,module.getName(),module.isEnabled()?"enabled":"transitive");
            }
        }
    }

    /**
     * 启用模块
     *
     * @param name
     * @throws IOException
     */
    public void enable(String name) throws IOException {
        List<String> empty = Collections.emptyList();
        enable(name,empty);
    }

    /**
     * 启用模块
     *
     * @param name
     * @param sources
     * @throws IOException
     */
    public void enable(String name, List<String> sources) throws IOException {
        if (name.contains("*")) {
            // A regex!
            Pattern pat = Pattern.compile(name);
            List<Module> matching = new ArrayList<>();
            do {
                matching.clear();
                
                // find matching entries that are not enabled
                for (Map.Entry<String, Module> entry : modules.entrySet()) {
                    if (pat.matcher(entry.getKey()).matches()) {
                        if (!entry.getValue().isEnabled()) {
                            matching.add(entry.getValue());
                        }
                    }
                }
                
                // enable them
                for (Module module : matching) {
                    enableModule(module,sources);
                }
            }
            while (!matching.isEmpty());
        } else {
            Module module = modules.get(name);
            if (module == null) {
                System.err.printf("WARNING: Cannot enable requested module [%s]: not a valid module name.%n",name);
                return;
            }
            enableModule(module,sources);
        }
    }

    /**
     * 启用模块
     *
     * @param module
     * @param sources
     * @throws IOException
     */
    private void enableModule(Module module, List<String> sources) throws IOException {
        String via = "<transitive>";

        // Always add the sources
        if (sources != null) {
            module.addSources(sources);
            via = Main.join(sources, ", ");
        }
        
        // If already enabled, nothing else to do
        if (module.isEnabled()) {
            StartLog.debug("Enabled module: %s (via %s)",module.getName(),via);
            return;
        }
        
        StartLog.debug("Enabling module: %s (via %s)",module.getName(),via);
        module.setEnabled(true);
        args.parseModule(module);
        module.expandProperties(args.getProperties());
        
        // enable any parents that haven't been enabled (yet)
        Set<String> parentNames = new HashSet<>();
        parentNames.addAll(module.getParentNames());
        for(String name: parentNames) {
            StartLog.debug("Enable parent '%s' of module: %s",name,module.getName());
            Module parent = modules.get(name);
            if (parent == null) {
                // parent module doesn't exist, yet
                Path file = baseHome.getPath("modules/" + name + ".mod");
                if (FS.canReadFile(file)) {
                    parent = registerModule(file);
                    parent.expandProperties(args.getProperties());
                    updateParentReferencesTo(parent);
                } else {
                    if (!Props.hasPropertyKey(name)) {
                        StartLog.debug("Missing module definition: [ Mod: %s | File: %s ]",name,file);
                        missingModules.add(name);
                    }
                }
            }
            if (parent != null) {
                enableModule(parent,null);
            }
        }
    }

    /**
     * 获取子模块
     *
     * @param module
     * @param ret
     */
    private void findChildren(Module module, Set<Module> ret) {
        ret.add(module);
        for (Module child : module.getChildEdges()) {
            ret.add(child);
        }
    }

    /**
     * 获取父模块
     * 这里用递归来实现，因为一个父模块可能还有父模块
     *
     * @param module
     * @param ret
     */
    private void findParents(Module module, Map<String, Module> ret) {
        ret.put(module.getName(),module);
        for (Module parent : module.getParentEdges()) {
            ret.put(parent.getName(),parent);
            findParents(parent,ret);
        }
    }

    /**
     * 获取特定的模块
     *
     * @param name
     * @return
     */
    public Module get(String name) {
        return modules.get(name);
    }

    /**
     * 获取最大深度
     *
     * @return
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * 获取某个深度的模块集合
     *
     * @param depth
     * @return
     */
    public Set<Module> getModulesAtDepth(int depth) {
        Set<Module> ret = new HashSet<>();
        for (Module module : modules.values()) {
            if (module.getDepth() == depth) {
                ret.add(module);
            }
        }
        return ret;
    }

    @Override
    public Iterator<Module> iterator()
    {
        return modules.values().iterator();
    }

    /**
     * 获取库列表
     *
     * @param active
     * @return
     */
    public List<String> normalizeLibs(List<Module> active) {
        List<String> libs = new ArrayList<>();
        for (Module module : active) {
            for (String lib : module.getLibs()) {
                if (!libs.contains(lib)) {
                    libs.add(lib);
                }
            }
        }
        return libs;
    }

    /**
     * 获取xml列表
     *
     * @param active
     * @return
     */
    public List<String> normalizeXmls(List<Module> active) {
        List<String> xmls = new ArrayList<>();
        for (Module module : active) {
            for (String xml : module.getXmls()) {
                if (!xmls.contains(xml)) {
                    xmls.add(xml);
                }
            }
        }
        return xmls;
    }

    /**
     * 注册
     *
     * @param module
     * @return
     */
    public Module register(Module module) {
        modules.put(module.getName(),module);
        return module;
    }

    /**
     * 注册父模块
     *
     * @param module
     * @throws IOException
     */
    public void registerParentsIfMissing(Module module) throws IOException {
        Set<String> parents = new HashSet<>(module.getParentNames());
        for (String name : parents) {
            if (!modules.containsKey(name)) {
                Path file = baseHome.getPath("modules/" + name + ".mod");
                if (FS.canReadFile(file)) {
                    Module parent = registerModule(file);
                    updateParentReferencesTo(parent);
                    registerParentsIfMissing(parent);
                }
            }
        }
    }

    /**
     * 注册所有模块
     *
     * @throws IOException
     */
    public void registerAll() throws IOException {
        for (Path path : baseHome.getPaths("modules/*.mod")) {
            registerModule(path);
        }
    }

    /**
     *  load missing post-expanded dependent modules
     *
     *  解析依赖
     *
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void normalizeDependencies() throws FileNotFoundException, IOException {
        Set<String> expandedModules = new HashSet<>();
        boolean done = false;
        while (!done) {
            done = true;
            Set<String> missingParents = new HashSet<>();

            for (Module m : modules.values()) {
                for (String parent : m.getParentNames()) {
                    String expanded = args.getProperties().expand(parent);
                    if (modules.containsKey(expanded) || missingModules.contains(parent) || expandedModules.contains(parent)) {
                        continue; // found. skip it.
                    }
                    done = false;
                    StartLog.debug("Missing parent module %s == %s for %s",parent,expanded,m);
                    missingParents.add(parent);
                }
            }

            for (String missingParent : missingParents) {
                String expanded = args.getProperties().expand(missingParent);
                Path file = baseHome.getPath("modules/" + expanded + ".mod");
                if (FS.canReadFile(file)) {
                    Module module = registerModule(file);
                    updateParentReferencesTo(module);
                    if (!expanded.equals(missingParent)) {
                        expandedModules.add(missingParent);
                    }
                } else {
                    if (Props.hasPropertyKey(expanded)) {
                        StartLog.debug("Module property not expandable (yet) [%s]",expanded);
                        expandedModules.add(missingParent);
                    } else {
                        StartLog.debug("Missing module definition: %s expanded to %s",missingParent,expanded);
                        missingModules.add(missingParent);
                    }
                }
            }
        }
    }

    /**
     * 注册模块
     *
     * @param file
     * @return
     * @throws FileNotFoundException
     * @throws IOException
     */
    private Module registerModule(Path file) throws FileNotFoundException, IOException {
        if (!FS.canReadFile(file)) {
            throw new IOException("Cannot read file: " + file);
        }
        StartLog.debug("Registering Module: %s",baseHome.toShortForm(file));
        Module module = new Module(baseHome,file);
        return register(module);
    }

    /**
     * 解析某个模块的子模块
     *
     * @param moduleName
     * @return
     */
    public Set<String> resolveChildModulesOf(String moduleName) {
        Set<Module> ret = new HashSet<>();
        Module module = get(moduleName);
        findChildren(module,ret);
        return asNameSet(ret);
    }

    /**
     * Resolve the execution order of the enabled modules, and all dependant modules, based on depth first transitive reduction.
     *
     * 解析所有可用的模块
     *
     * @return the list of active modules (plus dependant modules), in execution order.
     */
    public List<Module> resolveEnabled() {
        Map<String, Module> active = new HashMap<String, Module>();

        for (Module module : modules.values()) {
            if (module.isEnabled()) {
                findParents(module,active);
            }
        }

        /**
         * check against the missing modules
         * 
         * Ex: npn should match anything under npn/
         */
        for (String missing : missingModules) {
            for (String activeModule : active.keySet()) {
                if (missing.startsWith(activeModule)) {
                    StartLog.warn("** Unable to continue, required dependency missing. [%s]",missing);
                    StartLog.warn("** As configured, Jetty is unable to start due to a missing enabled module dependency.");
                    StartLog.warn("** This may be due to a transitive dependency akin to spdy on npn, which resolves based on the JDK in use.");
                    throw new UsageException(UsageException.ERR_BAD_ARG, "Missing referenced dependency: " + missing);
                }
            }
        }

        List<Module> ordered = new ArrayList<>();
        ordered.addAll(active.values());
        Collections.sort(ordered,new Module.DepthComparator());
        return ordered;
    }

    /**
     * 解析特定模块的父模块
     *
     * @param moduleName
     * @return
     */
    public Set<String> resolveParentModulesOf(String moduleName) {
        Map<String, Module> ret = new HashMap<>();
        Module module = get(moduleName);
        findParents(module,ret);
        return ret.keySet();
    }

    /**
     * 缩进
     *
     * @param depth
     * @return
     */
    private String toIndent(int depth) {
        char indent[] = new char[depth * 2];
        Arrays.fill(indent,' ');
        return new String(indent);
    }

    /**
     * Modules can have a different logical name than to their filesystem reference.
     * This updates existing references to the filesystem form to use the logical name form.
     *
     * 更新父模块的名称
     *
     * @param module
     *            the module that might have other modules referring to it.
     */
    private void updateParentReferencesTo(Module module) {
        if (module.getName().equals(module.getFilesystemRef())) {
            // nothing to do, its sane already
            return;
        }

        for (Module m : modules.values()) {
            Set<String> resolvedParents = new HashSet<>();
            for (String parent : m.getParentNames()) {
                if (parent.equals(module.getFilesystemRef())) {
                    // use logical name instead
                    resolvedParents.add(module.getName());
                } else {
                    // use name as-is
                    resolvedParents.add(parent);
                }
            }
            m.setParentNames(resolvedParents);
        }
    }

    /**
     * 转换为字符串
     *
     * @return
     */
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Modules[");
        str.append("count=").append(modules.size());
        str.append(",<");
        boolean delim = false;
        for (String name : modules.keySet()) {
            if (delim) {
                str.append(',');
            }
            str.append(name);
            delim = true;
        }
        str.append(">");
        str.append("]");
        return str.toString();
    }

}
