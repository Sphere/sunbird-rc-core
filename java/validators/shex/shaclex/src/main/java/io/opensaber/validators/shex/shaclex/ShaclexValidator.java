package io.opensaber.validators.shex.shaclex;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import es.weso.schema.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import scala.collection.JavaConverters;
import scala.Option;

import es.weso.rdf.RDFReader;
import es.weso.rdf.jena.RDFAsJenaModel;

public class ShaclexValidator {

	Option<String> none = Option.apply(null);

	public void validate(Model dataModel, Schema schema) {
		RDFReader rdf = new RDFAsJenaModel(dataModel);
		Result result = schema.validate(rdf,"TARGETDECLS",none,none, rdf.getPrefixMap(), schema.pm());
		if (result.isValid()) {
			System.out.println("Result is valid");
			System.out.println("Valid. Result: " + result.show());
			List<Solution> solutions = JavaConverters.seqAsJavaList(result.solutions());
			solutions.forEach((solution) ->
			System.out.println(solution.show()));
		} else {
			System.out.println("Not valid");
			List<ErrorInfo> errors = JavaConverters.seqAsJavaList(result.errors());
			errors.forEach((e) ->
			System.out.println(e.show()));
		}
	} 

	public void validate(String dataJSONLD, String schemaFile, String schemaFormat, String processor) throws IOException {
		System.out.println("Reading data JSONLD " + dataJSONLD);
		Model dataModel = parse(dataJSONLD);//RDFDataMgr.loadModel(dataFile);
		System.out.println("Model read. Size = " + dataModel.size());
		System.out.println(dataModel);
		System.out.println("Reading shapes file " + schemaFile + " with format " + schemaFormat);
		Schema schema = readSchema(Paths.get(schemaFile),schemaFormat, processor);
		System.out.println("Schema read" + schema.serialize(schemaFormat));
		validate(dataModel,schema);
	}    
	
	private Model parse(String jsonld) {
        Model m = ModelFactory.createDefaultModel();
        StringReader reader = new StringReader(jsonld);
        m.read(reader, null, "JSON-LD");
        return m;
	}

	public Schema readSchema(Path schemaFilePath, String format, String processor) throws IOException {
		String contents = new String(Files.readAllBytes(schemaFilePath));
		return Schemas.fromString(contents,format,processor,none).get();
	}

}
