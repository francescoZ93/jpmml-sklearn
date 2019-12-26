/*
 * Copyright (c) 2019 Villu Ruusmann
 *
 * This file is part of JPMML-SkLearn
 *
 * JPMML-SkLearn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-SkLearn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-SkLearn.  If not, see <http://www.gnu.org/licenses/>.
 */
package sklearn.ensemble.hist_gradient_boosting;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.dmg.pmml.DataType;
import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.SimplePredicate;
import org.dmg.pmml.True;
import org.dmg.pmml.mining.MiningModel;
import org.dmg.pmml.mining.Segmentation;
import org.dmg.pmml.tree.BranchNode;
import org.dmg.pmml.tree.LeafNode;
import org.dmg.pmml.tree.Node;
import org.dmg.pmml.tree.TreeModel;
import org.jpmml.converter.BinaryFeature;
import org.jpmml.converter.ContinuousFeature;
import org.jpmml.converter.ContinuousLabel;
import org.jpmml.converter.Feature;
import org.jpmml.converter.ModelUtil;
import org.jpmml.converter.PredicateManager;
import org.jpmml.converter.Schema;
import org.jpmml.converter.mining.MiningModelUtil;

public class HistGradientBoostingUtil {

	private HistGradientBoostingUtil(){
	}

	static
	public MiningModel encodeHistGradientBoosting(List<List<TreePredictor>> predictors, List<? extends Number> baselinePredictions, int column, Schema schema){
		List<TreePredictor> treePredictors = predictors.stream()
			.map(predictor -> predictor.get(column))
			.collect(Collectors.toList());

		Number baselinePrediction = baselinePredictions.get(column);

		return encodeHistGradientBoosting(treePredictors, baselinePrediction, schema);
	}

	static
	public MiningModel encodeHistGradientBoosting(List<TreePredictor> treePredictors, Number baselinePrediction, Schema schema){
		ContinuousLabel continuousLabel = (ContinuousLabel)schema.getLabel();

		Schema segmentSchema = schema.toAnonymousRegressorSchema(DataType.DOUBLE);

		List<TreeModel> treeModels = new ArrayList<>();

		for(TreePredictor treePredictor : treePredictors){
			TreeModel treeModel = HistGradientBoostingUtil.encodeTreeModel(treePredictor, segmentSchema);

			treeModels.add(treeModel);
		}

		MiningModel miningModel = new MiningModel(MiningFunction.REGRESSION, ModelUtil.createMiningSchema(continuousLabel))
			.setSegmentation(MiningModelUtil.createSegmentation(Segmentation.MultipleModelMethod.SUM, treeModels))
			.setTargets(ModelUtil.createRescaleTargets(null, baselinePrediction, continuousLabel));

		return miningModel;
	}

	static
	public TreeModel encodeTreeModel(TreePredictor treePredictor, Schema schema){
		PredicateManager predicateManager = new PredicateManager();

		return encodeTreeModel(treePredictor, predicateManager, schema);
	}

	static
	public TreeModel encodeTreeModel(TreePredictor treePredictor, PredicateManager predicateManager, Schema schema){
		int[] leaf = treePredictor.isLeaf();
		int[] leftChildren = treePredictor.getLeft();
		int[] rightChildren = treePredictor.getRight();
		int[] featureIdx = treePredictor.getFeatureIdx();
		double[] thresholds = treePredictor.getThreshold();
		double[] values = treePredictor.getValues();

		Node root = encodeNode(True.INSTANCE, predicateManager, 0, leaf, leftChildren, rightChildren, featureIdx, thresholds, values, schema);

		TreeModel treeModel = new TreeModel(MiningFunction.REGRESSION, ModelUtil.createMiningSchema(schema.getLabel()), root)
			.setSplitCharacteristic(TreeModel.SplitCharacteristic.BINARY_SPLIT);

		return treeModel;
	}

	static
	private Node encodeNode(Predicate predicate, PredicateManager predicateManager, int index, int[] leaf, int[] leftChildren, int[] rightChildren, int[] featureIdx, double[] thresholds, double[] values, Schema schema){
		Integer id = Integer.valueOf(index);

		if(leaf[index] == 0){
			Feature feature = schema.getFeature(featureIdx[index]);

			double threshold = thresholds[index];

			Predicate leftPredicate;
			Predicate rightPredicate;

			if(feature instanceof BinaryFeature){
				BinaryFeature binaryFeature = (BinaryFeature)feature;

				if(threshold != 0.5d){
					throw new IllegalArgumentException();
				}

				Object value = binaryFeature.getValue();

				leftPredicate = predicateManager.createSimplePredicate(binaryFeature, SimplePredicate.Operator.NOT_EQUAL, value);
				rightPredicate = predicateManager.createSimplePredicate(binaryFeature, SimplePredicate.Operator.EQUAL, value);
			} else

			{
				ContinuousFeature continuousFeature = feature.toContinuousFeature(DataType.DOUBLE);

				Double value = threshold;

				leftPredicate = predicateManager.createSimplePredicate(continuousFeature, SimplePredicate.Operator.LESS_OR_EQUAL, value);
				rightPredicate = predicateManager.createSimplePredicate(continuousFeature, SimplePredicate.Operator.GREATER_THAN, value);
			}

			Node leftChild = encodeNode(leftPredicate, predicateManager, leftChildren[index], leaf, leftChildren, rightChildren, featureIdx, thresholds, values, schema);
			Node rightChild = encodeNode(rightPredicate, predicateManager, rightChildren[index], leaf, leftChildren, rightChildren, featureIdx, thresholds, values, schema);

			Node result = new BranchNode(null, predicate)
				.setId(id)
				.addNodes(leftChild, rightChild);

			return result;
		} else

		if(leaf[index] == 1){
			Node result = new LeafNode(values[index], predicate)
				.setId(id);

			return result;
		} else

		{
			throw new IllegalArgumentException();
		}
	}
}