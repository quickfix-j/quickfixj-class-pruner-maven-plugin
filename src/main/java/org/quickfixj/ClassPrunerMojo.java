package org.quickfixj;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Goal that prunes specified classes 
 * This is intended to be used before javadoc and packaging to reduce memory requirements and build time
 */
@Mojo( name = "prune", defaultPhase = LifecyclePhase.INSTALL )
public class ClassPrunerMojo extends AbstractMojo {
    private static final String XML_SUFFIX = ".xml";
    private static final String FIELD_TAG = "field";
    private static final String FIELDS_TAG = "fields";
    
    /**
     * <code>fileSet</code> defining the QFJ Dictionary Locations.
     */
    @Parameter ( property = "fileset", required = true )
    private FileSet fileset;

    /**
     * Location of classes to delete
     */
    @Parameter( defaultValue = "classes", property = "classesDirectory", required = true )
    private File classesDirectory;

    /**
     * Location of the generated source to delete
     */
    @Parameter( defaultValue = "generated-sources", property = "generatedSourcesDirectory", required = true )
    private File generatedSourcesDirectory;

    
    public void execute()
        throws MojoExecutionException
    {
        this.getLog().info("executing mojo.");
        
        if ( !classesDirectory.exists() || !classesDirectory.isDirectory() )
        {
            String errorMsg = classesDirectory.getAbsolutePath() + " must exist and be a directory.";
            this.getLog().error(errorMsg);
            throw new MojoExecutionException(errorMsg);
        } else {
            this.getLog().info("Classes Directory : " + classesDirectory.getAbsolutePath());
        }
        
        if ( !generatedSourcesDirectory.exists() || !generatedSourcesDirectory.isDirectory() )
        {
            String errorMsg = generatedSourcesDirectory.getAbsolutePath() + " must exist and be a directory.";
            this.getLog().error(errorMsg);
            throw new MojoExecutionException(errorMsg);
        } else {
            this.getLog().info("Generated Sources Directory : " + generatedSourcesDirectory.getAbsolutePath());
        }
        
        if (null == fileset) {
            String errorMsg = "fileset must not be null.";
            this.getLog().error(errorMsg);
            throw new MojoExecutionException( errorMsg );
        }

        Set<String> fieldNames = new HashSet<String>();

        collectFieldNames(fieldNames);
        
        try {
            pruneGeneratedSources(fieldNames);
            pruneClasses(fieldNames);
        } catch (IOException e) {
            String errorMsg = "Exception pruning directories.";
            this.getLog().error(errorMsg, e);
            throw new MojoExecutionException( errorMsg, e);
        }
    }

    private void pruneClasses(Set<String> fieldNames) throws IOException {
        prune(fieldNames, this.classesDirectory, ".class", "Java class", "Java classes");
    }

    private void pruneGeneratedSources(Set<String> fieldNames) throws IOException {
        prune(fieldNames, this.generatedSourcesDirectory, ".java", "Java source", "Java sources");
    }
    
    private void prune(Set<String> fieldNames, File targetDirectory, String fileSuffix, String descriptor,
            String pluralDescriptor) throws IOException {
        Set<String> files = listFiles(targetDirectory);
        Set<String> namesOfFilesToKeep = fieldNames.stream().map(file -> file + fileSuffix).collect(Collectors.toSet());
        files.removeAll(namesOfFilesToKeep);
        List<String> fileList = new ArrayList<String>(files);
        Collections.sort(fileList);
        this.getLog().info(pluralDescriptor + " to delete : " + fileList.size());
        for (String fileName  : fileList) {
            this.getLog().debug("Deleting " + descriptor + " : " + fileName);
            File file = new File( targetDirectory, fileName );
            Files.delete(file.toPath());
        }
    }
    
    private static Set<String> listFiles(File directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory.toPath())) {
            return stream
              .filter(file -> !Files.isDirectory(file))
              .map(Path::getFileName)
              .map(Path::toString)
              .filter(fileName -> !fileName.endsWith(XML_SUFFIX))
              .collect(Collectors.toSet());
        }
    }

    private void collectFieldNames(Set<String> fieldNames) throws MojoExecutionException {
        Set<String> fileNamesToParse = collectFileNameToParse();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db;
        try {
            db = dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            String msg = "ParserConfigurationException creating Document Builder";
            this.getLog().error(msg, e);
            throw new MojoExecutionException(msg, e);
        }
        
        for (String fileName: fileNamesToParse) {
            Document document;
            try {
                document = db.parse(new File(fileName));
                // optional, but recommended
                // http://stackoverflow.com/questions/13786607/normalization-in-dom-parsing-with-java-how-does-it-work
                document.getDocumentElement().normalize();
                addFieldNames(document.getDocumentElement(), fieldNames);
            } catch (SAXException | IOException e) {
                String msg = "Exception parsing file " + fileName;
                this.getLog().error(msg, e);
                throw new MojoExecutionException(msg, e);
            }
        }
        
        List<String> fieldList = new ArrayList<String>(fieldNames);
        Collections.sort(fieldList);
        for (String fieldName : fieldList) {
            this.getLog().debug("Found field : " + fieldName);
        }
        this.getLog().info("Found field total : " + fieldList.size());
    }

    private Set<String> collectFileNameToParse() {
        FileSetManager fileSetManager = new FileSetManager();
        this.getLog().info("fileset " + fileset.toString());
        
        String currentDir = System.getProperty("user.dir");
        this.getLog().info("Current working directory : " + currentDir);
        
        Set<String> fileNamesToParse = new HashSet<String>();
        
        String baseDirectory = fileset.getDirectory();
        for (String includedFile : fileSetManager.getIncludedFiles(fileset)) {
            this.getLog().debug("will parse file : " + includedFile);
            fileNamesToParse.add(new File(baseDirectory, includedFile).getPath());
        }
        return fileNamesToParse;
    }

    private static void addFieldNames(Element element, Set<String> fieldNames) {
        NodeList fieldsNodes = element.getElementsByTagName(FIELDS_TAG);
        for (int i = 0; i < fieldsNodes.getLength(); i++) {
            NodeList children = fieldsNodes.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE && FIELD_TAG.equals(child.getNodeName())) {
                    String name = ((Element) child).getAttribute("name");
                    if (!name.isEmpty()) {
                        fieldNames.add(name);
                    }
                }
            }
        }
    }
}
