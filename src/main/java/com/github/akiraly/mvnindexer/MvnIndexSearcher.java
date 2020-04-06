package com.github.akiraly.mvnindexer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.FlatSearchRequest;
import org.apache.maven.index.FlatSearchResponse;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.expr.SourcedSearchExpression;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

public class MvnIndexSearcher {

  public static void main(String[] args)
      throws PlexusContainerException, ComponentLookupException, IOException {
    DefaultContainerConfiguration config = new DefaultContainerConfiguration();
    config.setClassPathScanning(PlexusConstants.SCANNING_INDEX);
    DefaultPlexusContainer plexusContainer = new DefaultPlexusContainer(config);

    // lookup the indexer components from plexus
    Indexer indexer = plexusContainer.lookup(Indexer.class);
    IndexUpdater indexUpdater = plexusContainer.lookup(IndexUpdater.class);

    Wagon httpWagon = plexusContainer.lookup(Wagon.class, "http");

    // Files where local cache is (if any) and Lucene Index should be located
    File centralLocalCache = new File("target/central-cache");
    File centralIndexDir = new File("target/central-index");

    // Creators we want to use (search for fields it defines)
    List<IndexCreator> indexers = new ArrayList<>();
    indexers.add(plexusContainer.lookup(IndexCreator.class, "min"));
    indexers.add(plexusContainer.lookup(IndexCreator.class, "jarContent"));
    indexers.add(plexusContainer.lookup(IndexCreator.class, "maven-plugin"));

    // Create context for central repository index
    IndexingContext centralContext =
        indexer
            .createIndexingContext("central-context", "central", centralLocalCache, centralIndexDir,
                "https://repo.maven.apache.org/maven2/", null, true, true, indexers);

    // Update the index (incremental update will happen if this is not 1st run and files are not deleted)
    // This whole block below should not be executed on every app start, but rather controlled by some configuration
    // since this block will always emit at least one HTTP GET. Central indexes are updated once a week, but
    // other index sources might have different index publishing frequency.
    // Preferred frequency is once a week.
    if (false) {
      System.out.println("Updating Index...");
      System.out.println("This might take a while on first run, so please be patient!");
      // Create ResourceFetcher implementation to be used with IndexUpdateRequest
      // Here, we use Wagon based one as shorthand, but all we need is a ResourceFetcher implementation
      TransferListener listener = new AbstractTransferListener() {
        @Override
        public void transferStarted(TransferEvent transferEvent) {
          System.out.print("  Downloading " + transferEvent.getResource().getName());
        }

        @Override
        public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
        }

        @Override
        public void transferCompleted(TransferEvent transferEvent) {
          System.out.println(" - Done");
        }
      };
      ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(httpWagon, listener, null,
          null);

      Date centralContextCurrentTimestamp = centralContext.getTimestamp();
      IndexUpdateRequest updateRequest = new IndexUpdateRequest(centralContext, resourceFetcher);
      IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
      if (updateResult.isFullUpdate()) {
        System.out.println("Full update happened!");
      } else if (updateResult.getTimestamp().equals(centralContextCurrentTimestamp)) {
        System.out.println("No update needed, index is up to date!");
      } else {
        System.out.println(
            "Incremental update happened, change covered " + centralContextCurrentTimestamp + " - "
                + updateResult.getTimestamp() + " period.");
      }

      System.out.println();
    }

    System.out.println("Searching for SHA1 7ab67e6b20e5332a7fb4fdf2f019aec4275846c2");

    FlatSearchResponse response = indexer.searchFlat(new FlatSearchRequest(
        indexer.constructQuery(MAVEN.SHA1,
            new SourcedSearchExpression("7ab67e6b20e5332a7fb4fdf2f019aec4275846c2")
        )
        , centralContext));

    for (ArtifactInfo ai : response.getResults()) {
      System.out.println(ai.toString());
    }

    System.out.println("------");
    System.out.println("Total: " + response.getTotalHitsCount());
    System.out.println();

//    IndexSearcher searcher = centralContext.acquireIndexSearcher();
//    try {
//      IndexReader ir = searcher.getIndexReader();
//      Bits liveDocs = MultiFields.getLiveDocs(ir);
//      for (int i = 0; i < ir.maxDoc(); i++) {
//        if (liveDocs == null || liveDocs.get(i)) {
//          Document doc = ir.document(i);
//          requireNonNull(doc);
//          ArtifactInfo ai = IndexUtils.constructArtifactInfo(doc, centralContext);
//          requireNonNull(ai);
//          System.out
//              .println(ai.getGroupId() + ":" + ai.getArtifactId() + ":" + ai.getVersion() + ":"
//                  + ai.getClassifier() + " (sha1=" + ai.getSha1() + ")");
//        }
//      }
//    } finally {
//      centralContext.releaseIndexSearcher(searcher);
//    }
  }
}
