package wu.ac.at.owl.generation;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.RDF;

public class GeneratorMain {
    public static void main(String[] args) throws Exception {
        // Load properties
        Properties props = new Properties();
        props.load(new FileInputStream("benchmark.properties"));

        String[] datasets = props.getProperty("datasets").split(",");
        String inputDir = props.getProperty("inputDir");
        String outputDir = props.getProperty("outputDir");
        int admissionsPerPatient =
                Integer.parseInt(props.getProperty("admissionsPerPatient"));
        LocalDateTime start =
                LocalDateTime.parse(props.getProperty("start"));
        LocalDateTime end =
                LocalDateTime.parse(props.getProperty("end"));
        long seed =
                Long.parseLong(props.getProperty("seed"));

        // ONE cumulative output model for the whole run: every scale's obligations
        // are added on top of it
        Model out = ModelFactory.createDefaultModel();
        out.setNsPrefix("ex", ObligationGenerator.EX);
        out.setNsPrefix("emr", ObligationGenerator.EMR);
        out.setNsPrefix("gc", ObligationGenerator.GC);
        out.setNsPrefix("gem", ObligationGenerator.GEM);

        // ONE generator instance for the whole run: it's the paprt that remembers
        // which patients already have obligations, and the shared elapse time.
        GeneratorConfig sharedConfig = GeneratorConfig.fromDateTimes(
                null, null, admissionsPerPatient, start, end
        );
        ObligationGenerator generator = new ObligationGenerator(sharedConfig);

        // Patients we've already generated obligations for, across all scales so far.
        Set<String> seenPatientUris = new HashSet<>();

        // Loop over datasets (expected to be nested: each scale's patient file is a
        // superset of the previous scale's patient file)
        for (String dataset : datasets) {
            dataset = dataset.trim();
            String inputFile = inputDir + "patients-" + dataset + ".ttl";

            System.out.println("\n=== DATASET " + dataset + " ===");
            System.out.println("Input: " + inputFile);

            // Load patients for this scale
            Model patientModel = RDFDataMgr.loadModel(inputFile);
            Resource PatientClass = patientModel.createResource(
                    ObligationGenerator.EMR + "Patient");
            List<Resource> allPatients =
                    patientModel.listResourcesWithProperty(RDF.type, PatientClass).toList();

            System.out.println("Patients (cumulative): " + allPatients.size());

            // Only the patients not seen in a previous (smaller) scale get new obligations
            List<Resource> newPatients = new ArrayList<>();
            for (Resource p : allPatients) {
                if (!seenPatientUris.contains(p.getURI())) {
                    newPatients.add(p);
                }
            }
            for (Resource p : allPatients) {
                seenPatientUris.add(p.getURI());
            }

            int newObligations = newPatients.size() * admissionsPerPatient;
            int totalObligations = allPatients.size() * admissionsPerPatient;

            System.out.println("New patients this scale: " + newPatients.size());
            System.out.println("New obligations this scale: " + newObligations);
            System.out.println("Total obligations (cumulative): " + totalObligations);

            // Temporal cases only need to cover the NEW obligations added in this scale
            List<TemporalCase> temporalCases =
                    TemporalCaseAssigner.buildTemporalCases(newObligations, seed);

            // Adds new obligations on top of whatever is already in `out`
            generator.generate(out, newPatients, temporalCases);

            long nbOfGeneratedTriples = out.size();
            String outputFile = outputDir + "generated-obligations-" + totalObligations + ".ttl";

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                RDFDataMgr.write(fos, out, RDFFormat.TURTLE_PRETTY);
            }
            System.out.println("Generated triples (cumulative): " + nbOfGeneratedTriples);
        }
    }
}