package wu.ac.at.owl.generation;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

public class ObligationGenerator {

    // Namespaces
    public static final String EX  = "http://example.org/ex#";
    public static final String EMR = "http://wu.ac.at/domain/emr#";
    public static final String GC = "http://www.wu.ac.at/2026/obligation-core-model#";
    public static final String GEM = "http://www.wu.ac.at/2026/obligation-execution-model#";

    private final GeneratorConfig config;

    // --- State that must persist ACROSS multiple generate() calls so that
    //     scale N+1's file is scale N's file plus new obligations. ---
    private boolean initialized = false;
    private Resource elapse;
    private long finalElapseTime;
    private final Set<String> processedPatientUris = new HashSet<>();

    public ObligationGenerator(GeneratorConfig config) {
        this.config = config;
    }

    /**
     * Adds obligations to outModel for any patient in {@code patients} that hasn't
     * been processed by a previous call to this method (tracked internally by URI).
     * Patients already processed are skipped, so calling this repeatedly with a
     * growing, nested patient list accumulates obligations on top of what's
     * already in outModel - i.e. outModel after scale N+1 is a superset of
     * outModel after scale N.
     *
     * The shared "elapse" event time and ontology/schema triples are only
     * written once, on the first call, and reused for every later scale.
     *
     * @param temporalCasesForNewObligations must be sized exactly to
     *        (number of NEW patients among {@code patients}) * admissionFormsPerPatient
     */
    public void generate(Model outModel, List<Resource> patients, List<TemporalCase> temporalCasesForNewObligations) {

        initializeOnce(outModel);

        // Properties
        Property obligationContent   = outModel.createProperty(GC, "obligationContent");
        Property activationCondition = outModel.createProperty(GC, "activationCondition");
        Property startTime           = outModel.createProperty(GC, "numericStartTime");
        Property deadline            = outModel.createProperty(GC, "numericDeadline");
        Property atTime              = outModel.createProperty(GC, "numericAtTime");
        Property actionContent       = outModel.createProperty(GC, "actionContent");
        Property actionProp          = outModel.createProperty(GC, "action");
        Property entityProp          = outModel.createProperty(GC, "entity");
        Property resourceProp        = outModel.createProperty(GC, "resource");

        // Classes
        Resource Obligation      = outModel.createResource(GC + "Obligation");
        Resource TemporalAction  = outModel.createResource(GC + "TemporalAction");
        Resource RegulatedAction = outModel.createResource(GC + "RegulatedAction");
        Resource Patient        = outModel.createResource(EMR + "Patient");
        Resource AdmissionForm  = outModel.createResource(EMR + "AdmissionForm");
        Resource alwaysTriggered = outModel.createResource(GEM + "alwaysTriggered");

        int obligationIndex = 0;

        for (Resource patient : patients) {

            // Skip patients we've already generated obligations for in an earlier scale
            if (!processedPatientUris.add(patient.getURI())) {
                continue;
            }

            // add Patient
            outModel.add(patient, RDF.type, Patient);

            for (int i = 0; i < config.getAdmissionFormsPerPatient(); i++) {

                TemporalCase temporalCase = temporalCasesForNewObligations.get(obligationIndex);

                // Add Admission form
                Resource form = RandomUtils.randomResource(outModel, EMR, "admissionForm");
                outModel.add(form, RDF.type, AdmissionForm);

                // Obligation resources
                Resource obligation = RandomUtils.randomResource(outModel, EX, "obligation");
                Resource tempAction = RandomUtils.randomResource(outModel, EX, "obligation-content");
                Resource regAction  = RandomUtils.randomResource(outModel, EX, "action-content");

                // Obligation
                outModel.add(obligation, RDF.type, Obligation);
                outModel.add(obligation, obligationContent, tempAction);
                outModel.add(obligation, activationCondition, alwaysTriggered);

                // Temporal action
                outModel.add(tempAction, RDF.type, TemporalAction);
                outModel.add(tempAction, actionContent, regAction);

                // Generate start & deadline relative to the single shared elapse time
                long start = RandomUtils.randomEpochMillisSafe(
                        finalElapseTime - Duration.ofDays(10).toMillis(),
                        finalElapseTime - Duration.ofDays(5).toMillis()
                );

                long end = RandomUtils.randomEpochMillisSafe(
                        finalElapseTime + Duration.ofDays(5).toMillis(),
                        finalElapseTime + Duration.ofDays(10).toMillis()
                );

                outModel.add(tempAction, startTime, outModel.createTypedLiteral(start));
                outModel.add(tempAction, deadline, outModel.createTypedLiteral(end));

                // Apply temporal case
                switch (temporalCase) {

                    case ELAPSE_BETWEEN_START_AND_DEADLINE:
                        // do nothing
                        break;

                    case ACTION_BETWEEN_START_AND_DEADLINE:
                        long actionTime = RandomUtils.randomEpochMillisSafe(
                                start + Duration.ofMinutes(1).toMillis(),
                                finalElapseTime - Duration.ofMinutes(1).toMillis()
                        );
                        outModel.add(regAction, atTime, outModel.createTypedLiteral(actionTime));
                        break;

                    case DEADLINE_BEFORE_ELAPSE:
                        long newDeadline = RandomUtils.randomEpochMillisSafe(
                                start + Duration.ofHours(5).toMillis(),
                                finalElapseTime - Duration.ofHours(8).toMillis()
                        );
                        outModel.removeAll(tempAction, deadline, null);
                        outModel.add(tempAction, deadline, outModel.createTypedLiteral(newDeadline));
                        break;
                }

                // Regulated action
                outModel.add(regAction, RDF.type, RegulatedAction);
                outModel.add(regAction, entityProp, patient);
                outModel.add(regAction, resourceProp, form);

                // add random action
                Resource action = RandomUtils.randomResource(outModel, GC, "action");
                outModel.add(action, RDF.type, outModel.createResource(GC + "Action"));
                outModel.add(regAction, actionProp, action);

                obligationIndex++;
            }
        }
    }

    /**
     * Writes the ontology/schema triples and picks the single shared "elapse"
     * time once. Safe to call before every generate() invocation - it's a no-op
     * after the first call.
     */
    private void initializeOnce(Model outModel) {
        if (initialized) {
            return;
        }

        // Ontology triple
        String ontologyUri = "http://wu.ac.at/ocm/abox-" + java.util.UUID.randomUUID();
        Resource ontology = outModel.createResource(ontologyUri);
        ontology.addProperty(RDF.type, OWL.Ontology);
        ontology.addProperty(OWL.imports, outModel.createResource("http://wu.ac.at/2026/obligation-compliance-model"));

        Resource Patient         = outModel.createResource(EMR + "Patient");
        Resource AdmissionForm   = outModel.createResource(EMR + "AdmissionForm");
        Resource alwaysTriggered = outModel.createResource(GEM + "alwaysTriggered");
        Property atTime          = outModel.createProperty(GC, "numericAtTime");

        outModel.add(Patient, RDFS.subClassOf, outModel.createResource(GC + "Entity"));
        outModel.add(AdmissionForm, RDFS.subClassOf, outModel.createResource(GC + "Resource"));
        outModel.add(alwaysTriggered, RDF.type, outModel.createResource(GEM + "TriggeredEvent"));

        this.elapse = outModel.createResource(GC + "elapse");

        // final elapse time (epoch millis) - computed ONCE and shared by every scale
        this.finalElapseTime = RandomUtils.randomEpochMillisSafe(
                config.getMinTimeMillis(),
                config.getMaxTimeMillis()
        );
        outModel.add(elapse, atTime, outModel.createTypedLiteral(finalElapseTime));

        this.initialized = true;
    }
}