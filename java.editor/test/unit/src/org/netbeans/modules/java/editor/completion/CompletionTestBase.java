/**
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

package org.netbeans.modules.java.editor.completion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

import javax.swing.JEditorPane;
import javax.swing.text.Document;

import junit.framework.Assert;

import org.netbeans.api.editor.mimelookup.MimeLookup;
import org.netbeans.api.editor.mimelookup.MimePath;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.lexer.JavaTokenId;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtilsTestUtil2;
import org.netbeans.api.java.source.Task;
import org.netbeans.api.java.source.gen.WhitespaceIgnoringDiff;
import org.netbeans.api.lexer.Language;
import org.netbeans.core.startup.Main;
import org.netbeans.junit.NbTestCase;
import org.netbeans.modules.editor.completion.CompletionItemComparator;
import org.netbeans.modules.editor.java.JavaCompletionProvider;
import org.netbeans.modules.editor.java.JavaKit;
import org.netbeans.modules.java.JavaDataLoader;
//import org.netbeans.modules.java.source.TreeLoader;
import org.netbeans.modules.java.source.indexing.TransactionContext;
import org.netbeans.modules.java.source.parsing.JavacParserFactory;
import org.netbeans.modules.java.source.usages.BinaryAnalyser;
import org.netbeans.modules.java.source.usages.ClassIndexImpl;
import org.netbeans.modules.java.source.usages.ClassIndexManager;
import org.netbeans.modules.java.source.usages.IndexUtil;
import org.netbeans.modules.parsing.api.Source;
import org.netbeans.spi.editor.completion.CompletionItem;
import org.netbeans.spi.editor.completion.CompletionProvider;
import org.netbeans.spi.editor.mimelookup.MimeDataProvider;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.netbeans.spi.java.queries.SourceLevelQueryImplementation;
import org.openide.LifecycleManager;

import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileSystem;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.MultiFileSystem;
import org.openide.filesystems.Repository;
import org.openide.filesystems.XMLFileSystem;
import org.openide.loaders.DataObject;
import org.openide.util.Lookup;
import org.openide.util.SharedClassObject;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

/**
 *
 * @author Dusan Balek, Jan Lahoda
 */
public class CompletionTestBase extends NbTestCase {
    
    static {
        JavaCompletionItemElementCreatingTest.class.getClassLoader().setDefaultAssertionStatus(true);
        System.setProperty("org.openide.util.Lookup", Lkp.class.getName());
        Assert.assertEquals(Lkp.class, Lookup.getDefault().getClass());

        SourceUtilsTestUtil2.disableArtificalParameterNames();
    }

    static final int FINISH_OUTTIME = 5 * 60 * 1000;
    
    public static class Lkp extends ProxyLookup {
        
        private static Lkp DEFAULT;
        
        public Lkp() {
            Assert.assertNull(DEFAULT);
            DEFAULT = this;
        }
        
        public static void initLookups(Object[] objs) throws Exception {
            ClassLoader l = Lkp.class.getClassLoader();
            DEFAULT.setLookups(new Lookup [] {
                Lookups.fixed(objs),
                Lookups.metaInfServices(l),
                Lookups.singleton(l)
            });
        }
    }
    
    public CompletionTestBase(String testName) {
        super(testName);
    }
    
    private final AtomicReference<String> sourceLevel = new AtomicReference<String>();
    
    protected void setUp() throws Exception {
// this call did not do anything
//        GlobalSourcePathTestUtil.setUseLibraries (false);
        XMLFileSystem system = new XMLFileSystem();
        system.setXmlUrls(prepareLayers("META-INF/generated-layer.xml",
                                        "org/netbeans/modules/java/editor/resources/layer.xml",
                                        "org/netbeans/modules/defaults/mf-layer.xml"));
        Repository repository = new Repository(new MultiFileSystem(new FileSystem[] {FileUtil.createMemoryFileSystem(), system}));
        final ClassPath bootPath = createClassPath(System.getProperty("sun.boot.class.path"));
        ClassPathProvider cpp = new ClassPathProvider() {
            public ClassPath findClassPath(FileObject file, String type) {
                try {
                    if (type == ClassPath.SOURCE) {
                        return ClassPathSupport.createClassPath(new FileObject[]{FileUtil.toFileObject(getWorkDir())});
                    }
                    if (type == ClassPath.COMPILE) {
                        return ClassPathSupport.createClassPath(new FileObject[0]);
                    }
                    if (type == ClassPath.BOOT) {
                        return bootPath;
                    }
                } catch (IOException ex) {}
                return null;
            }
        };
        SharedClassObject loader = JavaDataLoader.findObject(JavaDataLoader.class, true);
        MimeDataProvider mdp = new MimeDataProvider() {
            public Lookup getLookup(MimePath mimePath) {
                return Lookups.fixed(new JavaKit(), new JavacParserFactory());
            }
        };
        SourceLevelQueryImplementation slq = new SourceLevelQueryImplementation() {
            @Override public String getSourceLevel(FileObject javaFile) {
                return sourceLevel.get();
            }
        };
        Lkp.initLookups(new Object[] {repository, loader, cpp, mdp, slq});
        File cacheFolder = new File(getWorkDir(), "var/cache/index");
        cacheFolder.mkdirs();
        IndexUtil.setCacheFolder(cacheFolder);
        JEditorPane.registerEditorKitForContentType("text/x-java", "org.netbeans.modules.editor.java.JavaKit");
        final ClassPath sourcePath = ClassPathSupport.createClassPath(new FileObject[] {FileUtil.toFileObject(getDataDir())});
        final ClassIndexManager mgr  = ClassIndexManager.getDefault();
        for (ClassPath.Entry entry : sourcePath.entries()) {
            TransactionContext tx = TransactionContext.beginStandardTransaction(entry.getURL(), true, true, false);
            try {
                mgr.createUsagesQuery(entry.getURL(), true);
            } finally {
                tx.commit();
            }
        }
        final ClasspathInfo cpInfo = ClasspathInfo.create(bootPath, ClassPathSupport.createClassPath(new URL[0]), sourcePath);
        assertNotNull(cpInfo);
        final JavaSource js = JavaSource.create(cpInfo);
        assertNotNull(js);
        js.runUserActionTask(new Task<CompilationController>() {
            public void run(CompilationController parameter) throws Exception {
                for (ClassPath.Entry entry : bootPath.entries()) {
                    final URL url = entry.getURL();
                    TransactionContext.beginStandardTransaction(entry.getURL(), false, true, false);
                    try {
                        final ClassIndexImpl cii = mgr.createUsagesQuery(url, false);
                        BinaryAnalyser ba = cii.getBinaryAnalyser();
                        ba.analyse(url);
                    } finally {
                        TransactionContext.get().commit();
                    }
                }
            }
        }, true);
        Main.initializeURLFactory();
        Preferences preferences = MimeLookup.getLookup(JavaTokenId.language().mimeType()).lookup(Preferences.class);
        preferences.putBoolean("completion-case-sensitive", true);
    }
    
    private URL[] prepareLayers(String... paths) throws IOException {
        List<URL> layers = new LinkedList<URL>();
        
        for (int cntr = 0; cntr < paths.length; cntr++) {
            boolean found = false;

            for (Enumeration<URL> en = Thread.currentThread().getContextClassLoader().getResources(paths[cntr]); en.hasMoreElements(); ) {
                found = true;
                layers.add(en.nextElement());
            }

            Assert.assertTrue(paths[cntr], found);
        }
        
        return layers.toArray(new URL[0]);
    }
    
    protected void tearDown() throws Exception {
    }
    
    protected void performTest(String source, int caretPos, String textToInsert, String toPerformItemRE, String goldenFileName) throws Exception {
        performTest(source, caretPos, textToInsert, toPerformItemRE, goldenFileName, null);
    }
    
    protected void performTest(String source, int caretPos, String textToInsert, String toPerformItemRE, String goldenFileName, String sourceLevel) throws Exception {
        this.sourceLevel.set(sourceLevel);
        File testSource = new File(getWorkDir(), "test/Test.java");
        testSource.getParentFile().mkdirs();
        copyToWorkDir(new File(getDataDir(), "org/netbeans/modules/java/editor/completion/data/" + source + ".java"), testSource);
        FileObject testSourceFO = FileUtil.toFileObject(testSource);
        assertNotNull(testSourceFO);
        DataObject testSourceDO = DataObject.find(testSourceFO);
        assertNotNull(testSourceDO);
        EditorCookie ec = (EditorCookie) testSourceDO.getCookie(EditorCookie.class);
        assertNotNull(ec);
        final Document doc = ec.openDocument();
        assertNotNull(doc);
        doc.putProperty(Language.class, JavaTokenId.language());
        doc.putProperty("mimeType", "text/x-java");
        int textToInsertLength = textToInsert != null ? textToInsert.length() : 0;
        if (textToInsertLength > 0)
            doc.insertString(caretPos, textToInsert, null);
        Source s = Source.create(doc);
        List<? extends CompletionItem> items = JavaCompletionProvider.query(s, CompletionProvider.COMPLETION_QUERY_TYPE, caretPos + textToInsertLength, caretPos + textToInsertLength);
        Collections.sort(items, CompletionItemComparator.BY_PRIORITY);
        
        String version = System.getProperty("java.specification.version") + "/";
        
        assertNotNull(goldenFileName);            

        Pattern p = Pattern.compile(toPerformItemRE);
        CompletionItem item = null;            
        for (CompletionItem i : items) {
            if (p.matcher(i.toString()).find()) {
                item = i;
                break;
            }
        }            
        assertNotNull(item);

        JEditorPane editor = new JEditorPane();
        editor.setDocument(doc);
        editor.setCaretPosition(caretPos + textToInsertLength);
        item.defaultAction(editor);

        File output = new File(getWorkDir(), getName() + ".out2");
        Writer out = new FileWriter(output);            
        out.write(doc.getText(0, doc.getLength()));
        out.close();

        File goldenFile = new File(getDataDir(), "/goldenfiles/org/netbeans/modules/java/editor/completion/JavaCompletionProviderTest/" + version + goldenFileName);
        File diffFile = new File(getWorkDir(), getName() + ".diff");

        assertFile(output, goldenFile, diffFile, new WhitespaceIgnoringDiff());
        
        LifecycleManager.getDefault().saveAll();
    }

    private void copyToWorkDir(File resource, File toFile) throws IOException {
        InputStream is = new FileInputStream(resource);
        OutputStream outs = new FileOutputStream(toFile);
        int read;
        while ((read = is.read()) != (-1)) {
            outs.write(read);
        }
        outs.close();
        is.close();
    }
    
    private static ClassPath createClassPath(String classpath) {
        StringTokenizer tokenizer = new StringTokenizer(classpath, File.pathSeparator);
        List/*<PathResourceImplementation>*/ list = new ArrayList();
        while (tokenizer.hasMoreTokens()) {
            String item = tokenizer.nextToken();
            File f = FileUtil.normalizeFile(new File(item));
            URL url = getRootURL(f);
            if (url!=null) {
                list.add(ClassPathSupport.createResource(url));
            }
        }
        return ClassPathSupport.createClassPath(list);
    }
    
    // XXX this method could probably be removed... use standard FileUtil stuff
    private static URL getRootURL  (File f) {
        URL url = null;
        try {
            if (isArchiveFile(f)) {
                url = FileUtil.getArchiveRoot(f.toURI().toURL());
            } else {
                url = f.toURI().toURL();
                String surl = url.toExternalForm();
                if (!surl.endsWith("/")) {
                    url = new URL(surl+"/");
                }
            }
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        }
        return url;
    }
    
    private static boolean isArchiveFile(File f) {
        // the f might not exist and so you cannot use e.g. f.isFile() here
        String fileName = f.getName().toLowerCase();
        return fileName.endsWith(".jar") || fileName.endsWith(".zip");    //NOI18N
    }    
}
