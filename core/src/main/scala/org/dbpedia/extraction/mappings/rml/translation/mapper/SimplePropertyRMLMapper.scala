package org.dbpedia.extraction.mappings.rml.translation.mapper

import org.dbpedia.extraction.mappings.SimplePropertyMapping
import org.dbpedia.extraction.mappings.rml.translation.dbf.DbfFunction
import org.dbpedia.extraction.mappings.rml.translation.model.RMLModel
import org.dbpedia.extraction.mappings.rml.translation.model.rmlresources._
import org.dbpedia.extraction.ontology.RdfNamespace

import scala.language.reflectiveCalls

/**
  * Creates RML Mapping from SimplePropertyMappings and adds the triples to the given model
  */
class SimplePropertyRMLMapper(rmlModel: RMLModel, mapping: SimplePropertyMapping) {

  def mapToModel() : List[RMLPredicateObjectMap] = {
    addSimplePropertyMapping()
  }

  def addSimplePropertyMapping() : List[RMLPredicateObjectMap] =
  {
    val uniqueUri = rmlModel.wikiTitle.resourceIri
    addSimplePropertyMappingToTriplesMap(uniqueUri, rmlModel.triplesMap)
  }

  def addIndependentSimplePropertyMapper() : List[RMLPredicateObjectMap] =
  {
    val uri = new RMLUri(rmlModel.wikiTitle.resourceIri + "/SimplePropertyMapping/" + mapping.ontologyProperty.name + "/" + mapping.templateProperty)
    val simplePropertyPom = rmlModel.rmlFactory.createRMLPredicateObjectMap(uri)
    addSimplePropertyToPredicateObjectMap(simplePropertyPom)

    List(simplePropertyPom)
  }

  def addSimplePropertyMappingToTriplesMap(uri: String, triplesMap: RMLTriplesMap) : List[RMLPredicateObjectMap] =
  {

    val simplePropertyMappingUri = new RMLUri(uri + "/SimplePropertyMapping/" + mapping.ontologyProperty.name + "/" + mapping.templateProperty)
    val simplePmPom = triplesMap.addPredicateObjectMap(simplePropertyMappingUri)

    simplePmPom.addDCTermsType(new RMLLiteral("simplePropertyMapping"))
    simplePmPom.addPredicate(new RMLUri(mapping.ontologyProperty.uri))

    addSimplePropertyToPredicateObjectMap(simplePmPom)


    List(simplePmPom)

  }

  private def addDatatype(rMLPredicateObjectMap: RMLObjectMap) = {
    if (mapping.unit != null) {
      rMLPredicateObjectMap.addDatatype(new RMLUri(mapping.unit.uri))
    }
  }

  private def addSimplePropertyToPredicateObjectMap(simplePmPom: RMLPredicateObjectMap) =
  {

      val functionTermMapUri = simplePmPom.uri.extend("/FunctionTermMap")
      val functionTermMap = simplePmPom.addFunctionTermMap(functionTermMapUri)

      // adds the unit datatype if there is one
      addDatatype(functionTermMap)

      // adds the function value for simplepropertymapping
      val functionValueUri = functionTermMapUri.extend("/FunctionValue")
      val functionValue = functionTermMap.addFunctionValue(functionValueUri)
      functionValue.addLogicalSource(rmlModel.logicalSource)
      functionValue.addSubjectMap(rmlModel.functionSubjectMap)


      // the next few lines check if the SimplePropertyFunction already exists or not in the mapping file so that
      // there is always a maximum of one ExecutePOMs of this function in a mapping
      if(!rmlModel.containsResource(new RMLUri(RdfNamespace.DBF.namespace + DbfFunction.simplePropertyFunction.name)))
      {
        createSimplePropertyFunction(functionValue)
      }
      else
      {
        functionValue.addPredicateObjectMap(new RMLUri(rmlModel.triplesMap.resource.getURI + "/Function/SimplePropertyFunction"))
      }

      // add the remaining parameters
      addParameters(functionValue)

  }

  private def addReferenceParameterFunction(param : String, functionValue: RMLTriplesMap) =
  {
    val parameterPomUri = functionValue.uri.extend("/" + param + "ParameterPOM")
    val parameterPom = functionValue.addPredicateObjectMap(parameterPomUri)
    parameterPom.addPredicate(new RMLUri(RdfNamespace.DBF.namespace + param + "Parameter"))
    val parameterObjectMapUri = parameterPomUri.extend("/ObjectMap")
    parameterPom.addObjectMap(parameterObjectMapUri).addRMLReference(new RMLLiteral(getParameterValue(param)))

  }

  private def addConstantParameterFunction(param : String, functionValue: RMLTriplesMap) =
  {
    val parameterPomUri = functionValue.uri.extend("/" + param + "ParameterPOM")
    val parameterPom = functionValue.addPredicateObjectMap(parameterPomUri)
    parameterPom.addPredicate(new RMLUri(RdfNamespace.DBF.namespace + param + "Parameter"))
    parameterPom.addObject(new RMLLiteral(getParameterValue(param)))
  }

  private def getParameterValue(param: String) : String =
  {

    param match {
      case "factor" => mapping.factor.toString
      case "transform" => mapping.transform
      case "select" => mapping.select
      case "prefix" => mapping.prefix
      case "suffix" => mapping.suffix
      case "unit" => mapping.unit.name
      case "property" => mapping.templateProperty
      case "ontologyProperty" => mapping.ontologyProperty.name
    }

  }

  /**
    * Adds an Execute Predicate Object Map (fixed uri) for the SimplePropertyFunction to a given FunctionValue
    * @param functionValue
    * @return
    */
  private def createSimplePropertyFunction(functionValue : RMLTriplesMap) = {
    val executePomUri = new RMLUri(rmlModel.triplesMap.resource.getURI + "/Function/SimplePropertyFunction") // to make the uri short and simple
    val executePom = functionValue.addPredicateObjectMap(executePomUri)
    executePom.addPredicate(new RMLUri(RdfNamespace.FNO.namespace + "executes"))
    executePom.addObject(new RMLUri(RdfNamespace.DBF.namespace + DbfFunction.simplePropertyFunction.name))
  }

  /**
    * Adds all the parameter POMs if neccessary
    * @param functionValue
    * @return
    */
  private def addParameters(functionValue: RMLTriplesMap) = {
    addReferenceParameterFunction("property", functionValue)

    if(mapping.factor != 1) {
      addConstantParameterFunction("factor", functionValue)
    }

    if(mapping.transform != null) {
      addConstantParameterFunction("transform", functionValue)
    }

    if(mapping.select != null) {
      addConstantParameterFunction("select", functionValue)
    }

    if(mapping.prefix != null) {
      addConstantParameterFunction("prefix", functionValue)
    }

    if(mapping.suffix != null) {
      addConstantParameterFunction("suffix", functionValue)
    }

    if(mapping.unit != null) {
      addConstantParameterFunction("unit", functionValue)
    }

    if(mapping.ontologyProperty != null) {
      addConstantParameterFunction("ontologyProperty", functionValue)
    }
  }


}