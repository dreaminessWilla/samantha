/*
 * Copyright (c) [2016-2017] [University of Minnesota]
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.grouplens.samantha.modeler.tensorflow;

import com.fasterxml.jackson.databind.JsonNode;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.commons.lang3.StringUtils;
import org.grouplens.samantha.modeler.common.LearningInstance;
import org.grouplens.samantha.modeler.featurizer.*;
import org.grouplens.samantha.modeler.model.AbstractLearningModel;
import org.grouplens.samantha.modeler.solver.IdentityFunction;
import org.grouplens.samantha.modeler.solver.ObjectiveFunction;
import org.grouplens.samantha.modeler.solver.StochasticOracle;
import org.grouplens.samantha.modeler.model.IndexSpace;
import org.grouplens.samantha.modeler.model.UncollectableModel;
import org.grouplens.samantha.modeler.model.VariableSpace;
import org.grouplens.samantha.server.exception.BadRequestException;
import org.grouplens.samantha.server.exception.ConfigurationException;
import org.tensorflow.Graph;
import org.tensorflow.Session;
import org.tensorflow.Tensor;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TensorFlowModel extends AbstractLearningModel implements Featurizer, UncollectableModel {
    private static final long serialVersionUID = 1L;
    transient private Graph graph;
    transient private Session session;
    private final List<FeatureExtractor> featureExtractors;
    private final String lossOperationName;
    private final String updateOperationName;
    private final String outputOperationName;
    private final String initOperationName;
    private final List<String> groupKeys;
    private final List<List<String>> equalSizeChecks;

    public static final String OOV = "";
    public static final int OOV_INDEX = 0;
    public static final String INDEX_APPENDIX = "_idx";
    public static final String VALUE_APPENDIX = "_val";

    public TensorFlowModel(Graph graph,
                           IndexSpace indexSpace, VariableSpace variableSpace,
                           List<FeatureExtractor> featureExtractors,
                           String lossOperationName, String updateOperationName,
                           String outputOperationName, String initOperationName,
                           List<String> groupKeys, List<List<String>> equalSizeChecks) {
        super(indexSpace, variableSpace);
        this.graph = graph;
        this.session = new Session(graph);
        this.groupKeys = groupKeys;
        this.equalSizeChecks = equalSizeChecks;
        this.featureExtractors = featureExtractors;
        this.lossOperationName = lossOperationName;
        this.updateOperationName = updateOperationName;
        this.outputOperationName = outputOperationName;
        this.initOperationName = initOperationName;
        session.runner().addTarget(initOperationName).run();
    }

    public double[] predict(LearningInstance ins) {
        List<LearningInstance> instances = new ArrayList<>(1);
        instances.add(ins);
        double[][] preds = predict(instances);
        double[] insPreds = new double[preds[0].length];
        for (int i=0; i<preds[0].length; i++) {
            insPreds[i] = preds[0][i];
        }
        return insPreds;
    }

    public double[][] predict(List<LearningInstance> instances) {
        Session.Runner runner = session.runner();
        Map<String, Tensor> feedDict = getFeedDict(instances);
        for (Map.Entry<String, Tensor> entry : feedDict.entrySet()) {
            runner.feed(entry.getKey(), entry.getValue());
        }
        runner.fetch(outputOperationName);
        List<Tensor<?>> results = runner.run();
        for (Map.Entry<String, Tensor> entry : feedDict.entrySet()) {
            entry.getValue().close();
        }
        Tensor<?> tensorOutput = results.get(0);
        if (tensorOutput.numDimensions() != 2) {
            throw new BadRequestException(
                    "The TensorFlow model should always predict with a two dimensional tensor.");
        }
        long[] outputShape = tensorOutput.shape();
        double[][] preds = new double[(int)outputShape[0]][(int)outputShape[1]];
        DoubleBuffer buffer = DoubleBuffer.allocate(tensorOutput.numElements());
        tensorOutput.writeTo(buffer);
        int k = 0;
        for (int i=0; i<instances.size(); i++) {
            for (int j=0; j<preds[0].length; j++) {
                preds[i][j] = buffer.get(k++);
            }
        }
        for (int i=0; i<results.size(); i++) {
            results.get(i).close();
        }
        buffer.clear();
        return preds;
    }

    public LearningInstance featurize(JsonNode entity, boolean update) {
        Map<String, List<Feature>> feaMap = FeaturizerUtilities.getFeatureMap(entity, true,
                featureExtractors, indexSpace);
        for (List<String> features : equalSizeChecks) {
            int size = -1;
            for (String fea : features) {
                if (size < 0) {
                    size = feaMap.get(fea).size();
                } else if (size != feaMap.get(fea).size()) {
                    throw new ConfigurationException(
                            "Equal size checks with " + features.toString() +
                                    " failed for " + entity.toString());
                }
            }
        }
        String group = null;
        if (groupKeys != null && groupKeys.size() > 0) {
            group = FeatureExtractorUtilities.composeConcatenatedKey(entity, groupKeys);
        }
        TensorFlowInstance instance = new TensorFlowInstance(group);
        for (Map.Entry<String, List<Feature>> entry : feaMap.entrySet()) {
            DoubleList doubles = new DoubleArrayList();
            IntList ints = new IntArrayList();
            for (Feature feature : entry.getValue()) {
                doubles.add(feature.getValue());
                ints.add(feature.getIndex());
            }
            double[] darr = doubles.toDoubleArray();
            instance.putValues(entry.getKey(), darr);
            int[] iarr = ints.toIntArray();
            instance.putIndices(entry.getKey(), iarr);
        }
        return instance;
    }

    private void getNumCols(List<LearningInstance> instances, Map<String, Integer> numCols) {
        for (LearningInstance instance : instances) {
            TensorFlowInstance tfins = (TensorFlowInstance) instance;
            for (Map.Entry<String, int[]> entry : tfins.getName2Indices().entrySet()) {
                String name = entry.getKey();
                int[] values = entry.getValue();
                int cur = numCols.getOrDefault(name, 0);
                if (values.length > cur) {
                    numCols.put(name, values.length);
                }
            }
        }
    }

    private Map<String, Integer> getNumSparseFeatures(List<LearningInstance> instances) {
        Map<String, Integer> numFeatures = new HashMap<>();
        for (LearningInstance instance : instances) {
            TensorFlowInstance tfins = (TensorFlowInstance) instance;
            for (Map.Entry<String, int[]> entry : tfins.getName2Indices().entrySet()) {
                String name = entry.getKey();
                int[] values = entry.getValue();
                int cur = numFeatures.getOrDefault(name, 0);
                numFeatures.put(name, values.length + cur);
            }
        }
        return numFeatures;
    }

    public Map<String, String> getStringifiedSparseTensor(List<LearningInstance> instances) {
        Map<String, Integer> numFeatures = getNumSparseFeatures(instances);
        Map<String, String> tensorMap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : numFeatures.entrySet()) {
            String name = entry.getKey();
            int numFeature = entry.getValue();
            DoubleBuffer valBuffer = DoubleBuffer.allocate(numFeature);
            IntBuffer idxBuffer = IntBuffer.allocate(numFeature);
            for (LearningInstance instance : instances) {
                TensorFlowInstance tfins = (TensorFlowInstance) instance;
                double[] doubleValues = tfins.getName2Values().get(name);
                int[] intValues = tfins.getName2Indices().get(name);
                for (int i=0; i<doubleValues.length; i++) {
                    valBuffer.put(doubleValues[i]);
                }
                for (int i=0; i<intValues.length; i++) {
                    idxBuffer.put(intValues[i]);
                }
            }
            tensorMap.put(name + VALUE_APPENDIX, StringUtils.join(valBuffer.array(), ','));
            tensorMap.put(name + INDEX_APPENDIX, StringUtils.join(idxBuffer.array(), ','));
        }
        return tensorMap;
    }

    private int getFeatureBuffer(List<LearningInstance> instances, Map<String, Integer> numCols,
                                 Map<String, ByteBuffer> valBufferMap,
                                 Map<String, ByteBuffer> idxBufferMap) {
        int batch = instances.size();
        getNumCols(instances, numCols);
        for (Map.Entry<String, Integer> entry : numCols.entrySet()) {
            String name = entry.getKey();
            int numCol = entry.getValue();
            ByteBuffer valBuffer = ByteBuffer.allocate(batch * numCol * Double.BYTES);
            ByteBuffer idxBuffer = ByteBuffer.allocate(batch * numCol * Integer.BYTES);
            for (LearningInstance instance : instances) {
                TensorFlowInstance tfins = (TensorFlowInstance) instance;
                double[] doubleValues = tfins.getName2Values().get(name);
                int[] intValues = tfins.getName2Indices().get(name);
                for (int i=0; i<numCol; i++) {
                    if (i < intValues.length) {
                        valBuffer.putDouble(doubleValues[i]);
                        idxBuffer.putInt(intValues[i]);
                    } else {
                        valBuffer.putDouble(1.0);
                        idxBuffer.putInt(OOV_INDEX);
                    }
                }
            }
            valBufferMap.put(name, valBuffer);
            idxBufferMap.put(name, idxBuffer);
        }
        return batch;
    }

    private Map<String, Tensor> getFeedDict(List<LearningInstance> instances) {
        Map<String, Integer> numCols = new HashMap<>();
        Map<String, ByteBuffer> valBufferMap = new HashMap<>();
        Map<String, ByteBuffer> idxBufferMap = new HashMap<>();
        int batch = getFeatureBuffer(instances, numCols, valBufferMap, idxBufferMap);
        Map<String, Tensor> tensorMap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : numCols.entrySet()) {
            String name = entry.getKey();
            int numCol = entry.getValue();
            long[] shape = {batch, numCol};
            if (valBufferMap.containsKey(name)) {
                DoubleBuffer buffer = valBufferMap.get(name).asDoubleBuffer();
                buffer.rewind();
                tensorMap.put(name + VALUE_APPENDIX, Tensor.create(shape, buffer));
            }
            if (idxBufferMap.containsKey(name)) {
                IntBuffer buffer = idxBufferMap.get(name).asIntBuffer();
                buffer.rewind();
                tensorMap.put(name + INDEX_APPENDIX, Tensor.create(shape, buffer));
            }
        }
        return tensorMap;
    }

    public List<StochasticOracle> getStochasticOracle(List<LearningInstance> instances) {
        Session.Runner runner = session.runner();
        Map<String, Tensor> feedDict = getFeedDict(instances);
        for (Map.Entry<String, Tensor> entry : feedDict.entrySet()) {
            runner.feed(entry.getKey(), entry.getValue());
        }
        runner.addTarget(updateOperationName).fetch(lossOperationName);
        List<Tensor<?>> results = runner.run();
        for (Map.Entry<String, Tensor> entry : feedDict.entrySet()) {
            entry.getValue().close();
        }
        StochasticOracle oracle = new StochasticOracle(results.get(0).doubleValue(), 0.0, 1.0);
        results.get(0).close();
        List<StochasticOracle> oracles = new ArrayList<>(1);
        oracles.add(oracle);
        return oracles;
    }

    public ObjectiveFunction getObjectiveFunction() {
        return new IdentityFunction();
    }

    public void destroyModel() {
        if (session != null) {
            session.close();
        }
        if (graph != null) {
            graph.close();
        }
    }

    private void writeObject(ObjectOutputStream stream) throws IOException {
        stream.defaultWriteObject();
        byte[] graphDef = graph.toGraphDef();
        stream.writeInt(graphDef.length);
        stream.write(graph.toGraphDef());
    }

    private void readObject(ObjectInputStream stream) throws ClassNotFoundException, IOException {
        stream.defaultReadObject();
        int len = stream.readInt();
        byte[] graphDef = new byte[len];
        stream.readFully(graphDef);
        graph = new Graph();
        graph.importGraphDef(graphDef);
        session = new Session(graph);
        session.runner().addTarget(initOperationName).run();
    }
}
