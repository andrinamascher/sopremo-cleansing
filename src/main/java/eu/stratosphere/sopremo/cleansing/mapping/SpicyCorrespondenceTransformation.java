/***********************************************************************************************************************
 *
 * Copyright (C) 2010-2012 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/
package eu.stratosphere.sopremo.cleansing.mapping;

import it.unibas.spicy.model.expressions.Expression;
import it.unibas.spicy.model.generators.FunctionGenerator;
import it.unibas.spicy.model.generators.IValueGenerator;
import it.unibas.spicy.model.generators.NullValueGenerator;
import it.unibas.spicy.model.generators.SkolemFunctionGenerator;
import it.unibas.spicy.model.paths.PathExpression;
import it.unibas.spicy.model.paths.SetAlias;
import it.unibas.spicy.model.paths.VariableCorrespondence;
import it.unibas.spicy.model.paths.VariablePathExpression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.nfunk.jep.ASTConstant;
import org.nfunk.jep.ASTFunNode;
import org.nfunk.jep.ASTVarNode;
import org.nfunk.jep.Node;

import eu.stratosphere.sopremo.EvaluationContext;
import eu.stratosphere.sopremo.expressions.ConstantExpression;
import eu.stratosphere.sopremo.expressions.EvaluationExpression;
import eu.stratosphere.sopremo.expressions.ObjectCreation;
import eu.stratosphere.sopremo.expressions.PathSegmentExpression;

/**
 * Reads Spicy Correspondence, i.e. source attribute to target attribute and creates Sopremo transformation.
 * Attributes can be nested (no arrays yet) and transformations can include functions.
 * 
 * @author Andrina Mascher, Arvid Heise
 *
 */
public class SpicyCorrespondenceTransformation {
	
	private transient EvaluationContext context;

	public EvaluationContext getContext() {
		return context;
	}

	public void setContext(EvaluationContext context) {
		this.context = context;
	}

	public ObjectCreation createNestedObjectFromSpicyPaths(TreeMap<PathExpression, IValueGenerator> st_map, SetAlias setAlias) {
		Map<String, List<TargetAttributeCreation>> map = new HashMap<String, List<TargetAttributeCreation>>();
		int ignoreTargetPrefixSteps = setAlias.getBindingPathExpression().getPathSteps().size() + 1; //we always ignore 1 extra tuple access
		for(Entry<PathExpression, IValueGenerator> e : st_map.entrySet()) {
			//target
			List<String> targetPathList = e.getKey().getPathSteps();
			List<String> targetPathListTrunc = targetPathList.subList(ignoreTargetPrefixSteps, targetPathList.size()-1); //usually remove first 3 and last step
			addTargetAttributeCreationToMap(map, targetPathListTrunc, e.getValue());
		}
		return createdNestedObject(map);
	}
	
	public ObjectCreation createNestedObjectFromSpicyPaths(List<VariableCorrespondence> correspondences) {
		Map<String, List<TargetAttributeCreation>> map = new HashMap<String, List<TargetAttributeCreation>>();
		for(VariableCorrespondence varCor : correspondences) {
			List<String> targetPathList = EntityMappingUtil.getRelevantPathSteps(varCor.getTargetPath()); //split target and add first as key to map e.g. [v3,fullname,name]
			Expression function = varCor.getTransformationFunction();	//includes source paths e.g. [v4.usCongressBiographies.usCongressBiography.worksFor]
			addTargetAttributeCreationToMap(map, targetPathList, new FunctionGenerator(function));
		}
		return createdNestedObject(map);
	}
	
	public ObjectCreation createdNestedObject(Map<String, List<TargetAttributeCreation>> map) {
		ObjectCreation object = new ObjectCreation();
		for(Entry<String, List<TargetAttributeCreation>> entry : map.entrySet()) {
			List<TargetAttributeCreation> tacList = entry.getValue();
			if(tacList.size() == 1 && tacList.get(0).getTargetPath() == null){
				//reached leaf, calculate value
				TargetAttributeCreation tac = tacList.get(0);
				EvaluationExpression sopremoSourcePath = null;
				if(tac.getValueGen() instanceof FunctionGenerator) {
					Expression function = ((FunctionGenerator) tac.getValueGen()).getFunction();
					Node topNode = function.getJepExpression().getTopNode();
					sopremoSourcePath = processJepFunctionNode(topNode, function.getAttributePaths());					
				} else if(tac.getValueGen() instanceof SkolemFunctionGenerator){
					SkolemFunctionGenerator sourcePathSpicy = (SkolemFunctionGenerator) tac.getValueGen(); //TODO
					sopremoSourcePath = ConstantExpression.NULL;
				} else if(tac.getValueGen() instanceof NullValueGenerator) {
					sopremoSourcePath = ConstantExpression.NULL;
				}
				object.addMapping(entry.getKey(), sopremoSourcePath);
			} else {
				//reached multiple inner attributes, create map for inner level
				Map<String, List<TargetAttributeCreation>> innerMap = new HashMap<String, List<TargetAttributeCreation>>();
				for(TargetAttributeCreation tac : tacList) {
					//add innerTAC to inner map		
					addTargetAttributeCreationToMap(innerMap, tac.getTargetPath(), tac.getValueGen());
				}
				object.addMapping(entry.getKey(), createdNestedObject(innerMap));
			}
		}
		return object;
	}

	private void addTargetAttributeCreationToMap(Map<String, List<TargetAttributeCreation>> innerMap, List<String> pathList, IValueGenerator valueGen) {
		//create TAC
		TargetAttributeCreation innerTAC = new TargetAttributeCreation();
		innerTAC.setValue(valueGen);
		String innerKey = pathList.remove(0);
		if(pathList.size() > 0) {
			innerTAC.setTargetPath( pathList );						
		}
		//add to map
		if(innerMap.containsKey(innerKey)) {
			List<TargetAttributeCreation> list = innerMap.get(innerKey);
			list.add(innerTAC);
		} else {
			List<TargetAttributeCreation> list = new ArrayList<TargetAttributeCreation>();
			list.add(innerTAC);
			innerMap.put(innerKey, list);
		}
	}
	
	private EvaluationExpression processJepFunctionNode(Node topNode, List<VariablePathExpression> sourcePaths) {
		if(topNode instanceof ASTVarNode ) { //usual 1:1-mapping without a function
			return createFunctionSourcePath( ((ASTVarNode) topNode).getVarName(), sourcePaths ); 
		} else if(topNode instanceof ASTFunNode ) { //uses a function
			return JepFunctionFactory.create( (ASTFunNode) topNode, sourcePaths, this.context);
		} else if(topNode instanceof ASTConstant) {
			return new ConstantExpression( ((ASTConstant) topNode).getValue() );
		} 
		return null;
	}

	private PathSegmentExpression createFunctionSourcePath(String pathFromFunction, List<VariablePathExpression> sourcePaths) {
			//e.g. pathFromFunction = usCongress.usCongressBiographies.usCongressBiography.worksFor;
			String[] pathFromFunctionSteps = pathFromFunction.split("\\.");
	
			//chose suitable sourcePath that matches pathFromFunction
			//e.g. sourcePath[0] = v1.usCongressBiography.worksFor;
			for(VariablePathExpression exp : sourcePaths) {
				if(exp.getLastStep().equals(pathFromFunctionSteps[pathFromFunctionSteps.length-1])) 
					return EntityMappingUtil.convertSpicyPath("0", exp);
			}
			return null;
		}
}

/**
 * used in nested attributes: path to target and how target is calculated
 * 
 * @author Andrina
 *
 */
class TargetAttributeCreation {
	List<String> targetPath;
	IValueGenerator value; //includes function (e.g. + or split) and source paths
	
	public List<String> getTargetPath() {
		return targetPath;
	}
	public void setTargetPath(List<String> targetPath) {
		this.targetPath = targetPath;
	}
	public IValueGenerator getValueGen() {
		return value;
	}
	public void setValue(IValueGenerator value) {
		this.value = value;
	}
	public String toString() {
		return "TargetAttributeCreation: " + targetPath + " = " + value.toString(); 
	}
}