package org.grouplens.samantha.server.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import org.grouplens.samantha.modeler.featurizer.FeatureExtractorUtilities;
import org.grouplens.samantha.modeler.knn.KnnModelFeatureTrigger;
import org.grouplens.samantha.modeler.tree.SortingUtilities;
import org.grouplens.samantha.server.expander.EntityExpander;
import org.grouplens.samantha.server.expander.ExpanderUtilities;
import org.grouplens.samantha.server.io.IOUtilities;
import org.grouplens.samantha.server.io.RequestContext;
import play.Configuration;
import play.libs.Json;

import java.util.ArrayList;
import java.util.List;

public class UserKnnRetriever extends AbstractRetriever {
    private final Retriever retriever;
    private final KnnModelFeatureTrigger trigger;
    private final List<EntityExpander> expanders;
    private final String weightAttr;
    private final String scoreAttr;
    private final List<String> itemAttrs;
    private final List<String> userAttrs;

    public UserKnnRetriever(String weightAttr,
                            String scoreAttr,
                            List<String> userAttrs,
                            List<String> itemAttrs,
                            Retriever retriever,
                            KnnModelFeatureTrigger trigger,
                            List<EntityExpander> expanders,
                            Configuration config) {
        super(config);
        this.weightAttr = weightAttr;
        this.scoreAttr = scoreAttr;
        this.itemAttrs = itemAttrs;
        this.userAttrs = userAttrs;
        this.retriever = retriever;
        this.trigger = trigger;
        this.expanders = expanders;
    }

    public RetrievedResult retrieve(RequestContext requestContext) {
        String engineName = requestContext.getEngineName();
        JsonNode reqBody = requestContext.getRequestBody();
        List<ObjectNode> initial = new ArrayList<>();
        ObjectNode one = Json.newObject();
        IOUtilities.parseEntityFromJsonNode(reqBody, one);
        initial.add(one);
        List<ObjectNode> features = trigger.getTriggeredFeatures(initial);
        ArrayNode arrFeas = Json.newArray();
        Object2DoubleMap<String> items = new Object2DoubleOpenHashMap<>();
        Object2DoubleMap<String> feature2score = new Object2DoubleOpenHashMap<>();
        for (ObjectNode feature : features) {
            arrFeas.add(feature);
            String key = FeatureExtractorUtilities.composeConcatenatedKey(feature, userAttrs);
            feature2score.put(key, feature.get(scoreAttr).asDouble());
        }
        RequestContext pseudoReq = new RequestContext(arrFeas, engineName);
        RetrievedResult retrieved = retriever.retrieve(pseudoReq);
        List<ObjectNode> results = new ArrayList<>();
        for (ObjectNode entity : retrieved.getEntityList()) {
            double weight = 1.0;
            if (entity.has(weightAttr)) {
                weight = entity.get(weightAttr).asDouble();
            }
            if (weight >= 0.5) {
                String feature = FeatureExtractorUtilities.composeConcatenatedKey(entity, userAttrs);
                double score = feature2score.getDouble(feature);
                String key = FeatureExtractorUtilities.composeConcatenatedKey(entity, itemAttrs);
                if (items.containsKey(key)) {
                    items.put(key, items.getDouble(key) + weight * score);
                } else {
                    items.put(key, weight * score);
                    results.add(entity);
                }
            }
        }
        for (ObjectNode result : results) {
            String key = FeatureExtractorUtilities.composeConcatenatedKey(result, itemAttrs);
            result.put(scoreAttr, items.getDouble(key));
        }
        results = ExpanderUtilities.expand(results, expanders, requestContext);
        results.sort(SortingUtilities.jsonFieldReverseComparator(scoreAttr));
        return new RetrievedResult(results, results.size());
    }
}