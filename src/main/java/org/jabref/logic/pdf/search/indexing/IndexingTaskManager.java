package org.jabref.logic.pdf.search.indexing;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.jabref.gui.util.BackgroundTask;
import org.jabref.gui.util.DefaultTaskExecutor;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.LinkedFile;

/**
 * Wrapper around {@link LuceneIndexer} to execute all operations in the background.
 */
public class IndexingTaskManager extends BackgroundTask<Void> {

    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();
    private TaskExecutor taskExecutor;
    private int numOfIndexedFiles = 0;

    private final Object lock = new Object();
    private boolean isRunning = false;
    private boolean isBlockingNewTasks = false;

    public IndexingTaskManager(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
        showToUser(true);
        willBeRecoveredAutomatically(true);
        DefaultTaskExecutor.runInJavaFXThread(() -> {
            this.updateProgress(1, 1);
            this.titleProperty().set(Localization.lang("Indexing pdf files"));
        });
    }

    @Override
    protected Void call() throws Exception {
        synchronized (lock) {
            isRunning = true;
        }
        updateProgress();
        while (!taskQueue.isEmpty() && !isCanceled()) {
            taskQueue.poll().run();
            numOfIndexedFiles++;
            updateProgress();
        }
        synchronized (lock) {
            isRunning = false;
        }
        return null;
    }

    private void updateProgress() {
        DefaultTaskExecutor.runInJavaFXThread(() -> {
            updateMessage(Localization.lang("%0 of %1 entries added to the index", numOfIndexedFiles, numOfIndexedFiles + taskQueue.size()));
            updateProgress(numOfIndexedFiles, numOfIndexedFiles + taskQueue.size());
        });
    }

    private void enqueueTask(Runnable indexingTask) {
        if (!isBlockingNewTasks) {
            taskQueue.add(indexingTask);
            // What if already running?
            synchronized (lock) {
                if (!isRunning) {
                    isRunning = true;
                    this.executeWith(taskExecutor);
                    showToUser(false);
                }
            }
        }
    }

    public AutoCloseable blockNewTasks() {
        synchronized (lock) {
            isBlockingNewTasks = true;
        }
        return () -> {
            synchronized (lock) {
                isBlockingNewTasks = false;
            }
        };
    }

    public void createIndex(LuceneIndexer indexer) {
        enqueueTask(() -> indexer.createIndex());
    }

    public void updateIndex(LuceneIndexer indexer) {
        Set<String> pathsToRemove = indexer.getListOfFilePaths();
        for (BibEntry entry : indexer.getDatabaseContext().getEntries()) {
            enqueueTask(() -> indexer.addToIndex(entry));
            for (LinkedFile file : entry.getFiles()) {
                pathsToRemove.remove(file.getLink());
            }
        }
        for (String pathToRemove : pathsToRemove) {
            enqueueTask(() -> indexer.removeFromIndex(pathToRemove));
        }
    }

    public void addToIndex(LuceneIndexer indexer, BibEntry entry) {
        enqueueTask(() -> addToIndex(indexer, entry));
    }

    public void removeFromIndex(LuceneIndexer indexer, List<LinkedFile> linkedFiles) {
        for (LinkedFile file : linkedFiles) {
            enqueueTask(() -> indexer.removeFromIndex(file.getLink()));
        }
    }

    public void removeFromIndex(LuceneIndexer indexer, BibEntry entry) {
        enqueueTask(() -> removeFromIndex(indexer, entry));
    }

    public void updateIndex(LuceneIndexer indexer, BibEntry entry) {
        enqueueTask(() -> updateIndex(indexer, entry));
    }

    public void updateDatabaseName(String name) {
        DefaultTaskExecutor.runInJavaFXThread(() -> this.titleProperty().set(Localization.lang("Indexing for %0", name)));
    }
}
