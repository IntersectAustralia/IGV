/*
 * Copyright (c) 2007-2012 The Broad Institute, Inc.
 * SOFTWARE COPYRIGHT NOTICE
 * This software and its documentation are the copyright of the Broad Institute, Inc. All rights are reserved.
 *
 * This software is supplied without any warranty or guaranteed support whatsoever. The Broad Institute is not responsible for its use, misuse, or functionality.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 */

package org.broad.igv.data;

import org.broad.igv.AbstractHeadlessTest;
import org.broad.igv.PreferenceManager;
import org.broad.igv.feature.FeatureDB;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.feature.genome.GenomeListItem;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.track.TrackLoader;
import org.broad.igv.ui.action.LoadFromServerAction;
import org.broad.igv.util.ParsingUtils;
import org.broad.igv.util.ResourceLocator;
import org.broad.igv.util.TestUtils;
import org.broad.igv.util.Utilities;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Test loading the data we host. Sometimes it gets corrupted,
 * or we make backwards-incompatible changes
 * <p/>
 * User: jacob
 * Date: 2012-Jul-27
 */
public class HostedDataTest extends AbstractHeadlessTest {

    @Rule
    public TestRule testTimeout = new Timeout((int) 600e4);

    private PrintStream errorWriter = System.out;


    /**
     * Given a node, returns the "path" attribute from that
     * node and its children (recursively).
     *
     * @param topNode
     */
    private void getPathsFromNode(Node topNode, Set<ResourceLocator> paths) {
        String pKey = "path";
        String serverURLkey = "serverURL";

        try {
            NamedNodeMap attrs = topNode.getAttributes();
            Node pathNode = attrs.getNamedItem(pKey);
            String path = pathNode.getTextContent().trim();

            Node serverURLNode = attrs.getNamedItem(serverURLkey);
            String serverURL = null;
            if (serverURLNode != null) {
                serverURL = serverURLNode.getTextContent().trim();
            }

            ResourceLocator locator = new ResourceLocator(serverURL, path);
            paths.add(locator);

        } catch (NullPointerException e) {
            //pass, node doesn't have path attribute
        }

        NodeList nodes = topNode.getChildNodes();
        for (int nn = 0; nn < nodes.getLength(); nn++) {
            Node node = nodes.item(nn);
            getPathsFromNode(node, paths);
        }
    }

    /**
     * Test loading all the data hosted for each genome
     *
     * @throws Exception
     */

    @Test
    public void testLoadServerData() throws Exception {

        String outPath = TestUtils.DATA_DIR + "failed_loaded_files.txt";
        errorWriter = new PrintStream(outPath);

        List<GenomeListItem> serverSideGenomeList = getServerGenomes();


        Map<String, Exception> failedFiles = new LinkedHashMap<String, Exception>(10);
        LinkedHashSet<String> nodeURLs;
        Set<ResourceLocator> fileLocators = new LinkedHashSet<ResourceLocator>(100);

        for (GenomeListItem genomeItem : serverSideGenomeList) {

            String genomeURL = LoadFromServerAction.getGenomeDataURL(genomeItem.getId());

            TrackLoader loader = new TrackLoader();
            Genome curGenome = GenomeManager.getInstance().loadGenome(genomeItem.getLocation(), null);


            try {
                nodeURLs = LoadFromServerAction.getNodeURLs(genomeURL);
                if (nodeURLs == null) {
                    System.out.println("Warning: No Data found for " + genomeURL);
                    continue;
                }
            } catch (Exception e) {
                recordError(genomeURL, e, failedFiles);
                continue;
            }

            for (String nodeURL : nodeURLs) {

                fileLocators.clear();
                try {
                    InputStream is = ParsingUtils.openInputStreamGZ(new ResourceLocator(nodeURL));
                    Document xmlDocument = Utilities.createDOMDocumentFromXmlStream(is);
                    getPathsFromNode(xmlDocument, fileLocators);

                    for (ResourceLocator locator : fileLocators) {
                        FeatureDB.clearFeatures();

                        try {
                            System.out.println("Loading " + locator);
                            loader.load(locator, curGenome);
                        } catch (Exception e) {
                            recordError(locator.getPath(), e, failedFiles);
                        }
                    }

                } catch (Exception e) {
                    recordError(nodeURL, e, failedFiles);
                }
            }

        }

        for (Map.Entry<String, Exception> entry : failedFiles.entrySet()) {
            String item = entry.getKey();
            errorWriter.println(String.format("Exception loading file %s: %s", item, entry.getValue().getMessage()));
        }

        errorWriter.flush();
        errorWriter.close();
        assertEquals(0, failedFiles.size());

    }

    private void recordError(String path, Exception e, Map<String, Exception> failures) {
        failures.put(path, e);
        errorWriter.println(String.format("Exception loading %s: %s", path, e.getMessage()));
        errorWriter.flush();
    }

    private List<GenomeListItem> getServerGenomes() throws IOException {
        String genomeListPath = PreferenceManager.DEFAULT_GENOME_URL;
        PreferenceManager.getInstance().overrideGenomeServerURL(genomeListPath);
        List<GenomeListItem> serverSideItemList = GenomeManager.getInstance().getServerGenomeArchiveList(null);
        assertNotNull("Could not retrieve genome list from server", serverSideItemList);
        assertTrue("Genome list empty", serverSideItemList.size() > 0);
        return serverSideItemList;
    }


    @Ignore
    @Test
    public void testLoadServerGenomes() throws Exception {

        List<GenomeListItem> serverSideItemList = getServerGenomes();

        Map<GenomeListItem, Exception> failedGenomes = new LinkedHashMap<GenomeListItem, Exception>(10);

        int count = 0;
        for (GenomeListItem genome : serverSideItemList) {
            try {
                count++;
                tstLoadGenome(genome.getLocation());
                Runtime.getRuntime().gc();
            } catch (Exception e) {
                failedGenomes.put(genome, e);
            }
        }
        System.out.println("Attempted to load " + count + " genomes");
        System.out.println(failedGenomes.size() + " of them failed");
        for (Map.Entry<GenomeListItem, Exception> entry : failedGenomes.entrySet()) {
            GenomeListItem item = entry.getKey();
            System.out.println(String.format("Exception loading (%s\t%s\t%s): %s", item.getDisplayableName(),
                    item.getLocation(), item.getId(), entry.getValue()));
        }

        assertEquals(0, failedGenomes.size());
    }

    public void tstLoadGenome(String path) throws Exception {
        FeatureDB.clearFeatures();
        Genome genome = GenomeManager.getInstance().loadGenome(path, null);
        assertTrue(genome.getChromosomeNames().size() > 0);
    }

}