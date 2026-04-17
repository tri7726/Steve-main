package com.steve.ai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Logger;

/**
 * A lightweight, offline Vector Store alternative using TF-IDF for semantic-like search.
 * This stores long-term memory for Steve that persists across sessions.
 */
public class VectorStore {
    private static final Logger LOGGER = Logger.getLogger(VectorStore.class.getName());
    private List<MemoryDocument> documents = new ArrayList<>();
    private final File storageFile;
    private final Gson gson;
    
    // TF-IDF structures
    private transient Map<String, Integer> documentFrequency = new HashMap<>();
    private transient int totalDocuments = 0;

    public VectorStore(File dataDirectory, String steveName) {
        if (!dataDirectory.exists()) {
            dataDirectory.mkdirs();
        }
        this.storageFile = new File(dataDirectory, steveName + "_memory.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        load();
    }

    public static class MemoryDocument {
        public String text;
        public long timestamp;
        public MemoryPriority priority = MemoryPriority.NORMAL;

        public MemoryDocument(String text, long timestamp) {
            this.text = text;
            this.timestamp = timestamp;
        }

        public MemoryDocument(String text, long timestamp, MemoryPriority priority) {
            this.text = text;
            this.timestamp = timestamp;
            this.priority = priority != null ? priority : MemoryPriority.NORMAL;
        }
    }

    public void addMemory(String text) {
        addMemory(text, MemoryPriority.NORMAL);
    }

    public void addMemory(String text, MemoryPriority priority) {
        documents.add(new MemoryDocument(text, System.currentTimeMillis(), priority));
        recalculateTFIDF();
        save();
        LOGGER.fine("Added long-term memory (priority=" + priority + "): " + text);
    }

    public List<String> search(String query, int topK) {
        if (documents.isEmpty() || query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Integer> queryTf = getTermFrequency(query);
        
        // Priority queue to hold top-k results
        PriorityQueue<Map.Entry<MemoryDocument, Double>> pq = new PriorityQueue<>(
            Comparator.comparingDouble(Map.Entry::getValue)
        );

        for (MemoryDocument doc : documents) {
            double score = calculateSimilarity(queryTf, getTermFrequency(doc.text), doc);
            pq.offer(new AbstractMap.SimpleEntry<>(doc, score));
            if (pq.size() > topK) {
                pq.poll(); // Keep only top K
            }
        }

        List<String> results = new ArrayList<>();
        while (!pq.isEmpty()) {
            results.add(0, pq.poll().getKey().text); // Reverse order to get descending
        }
        return results;
    }

    private void recalculateTFIDF() {
        documentFrequency.clear();
        totalDocuments = documents.size();
        
        for (MemoryDocument doc : documents) {
            Set<String> uniqueTerms = getTermFrequency(doc.text).keySet();
            for (String term : uniqueTerms) {
                documentFrequency.put(term, documentFrequency.getOrDefault(term, 0) + 1);
            }
        }
    }

    private Map<String, Integer> getTermFrequency(String text) {
        Map<String, Integer> tf = new HashMap<>();
        String[] words = text.toLowerCase().replaceAll("[^a-z0-9\\s]", "").split("\\s+");
        for (String word : words) {
            if (word.length() > 2) { // Extremely simple stop-word / short word removal
                tf.put(word, tf.getOrDefault(word, 0) + 1);
            }
        }
        return tf;
    }

    private double calculateSimilarity(Map<String, Integer> queryTf, Map<String, Integer> docTf, MemoryDocument doc) {
        double dotProduct = 0.0;
        double queryNorm = 0.0;
        double docNorm = 0.0;

        Set<String> allTerms = new HashSet<>();
        allTerms.addAll(queryTf.keySet());
        allTerms.addAll(docTf.keySet());

        for (String term : allTerms) {
            double idf = Math.log((double) totalDocuments / (1 + documentFrequency.getOrDefault(term, 0)));
            
            double qWeight = queryTf.getOrDefault(term, 0) * idf;
            double dWeight = docTf.getOrDefault(term, 0) * idf;

            dotProduct += qWeight * dWeight;
            queryNorm += qWeight * qWeight;
            docNorm += dWeight * dWeight;
        }

        if (queryNorm == 0 || docNorm == 0) return 0.0;
        double score = dotProduct / (Math.sqrt(queryNorm) * Math.sqrt(docNorm));
        if (doc != null && doc.priority == MemoryPriority.HIGH) {
            score *= 1.5;
        }
        return score;
    }

    private void save() {
        try (FileWriter writer = new FileWriter(storageFile)) {
            gson.toJson(documents, writer);
        } catch (IOException e) {
            LOGGER.severe("Failed to save memory store: " + e.getMessage());
        }
    }

    private void load() {
        if (!storageFile.exists()) return;
        
        try (FileReader reader = new FileReader(storageFile)) {
            Type listType = new TypeToken<ArrayList<MemoryDocument>>(){}.getType();
            List<MemoryDocument> loaded = gson.fromJson(reader, listType);
            if (loaded != null) {
                // Fix legacy documents that have no priority field (Gson leaves it null)
                for (MemoryDocument doc : loaded) {
                    if (doc.priority == null) {
                        doc.priority = MemoryPriority.NORMAL;
                    }
                }
                this.documents = loaded;
                recalculateTFIDF();
                LOGGER.info("Loaded " + documents.size() + " memories from disk");
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to load memory store: " + e.getMessage());
        }
    }
}
