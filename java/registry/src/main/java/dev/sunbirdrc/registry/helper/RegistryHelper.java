package dev.sunbirdrc.registry.helper;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flipkart.zjsonpatch.JsonPatch;
import dev.sunbirdrc.actors.factory.PluginRouter;
import dev.sunbirdrc.pojos.PluginRequestMessage;
import dev.sunbirdrc.pojos.PluginRequestMessageCreator;
import dev.sunbirdrc.pojos.PluginResponseMessage;
import dev.sunbirdrc.pojos.SunbirdRCInstrumentation;
import dev.sunbirdrc.pojos.attestation.Action;
import dev.sunbirdrc.pojos.attestation.States;
import dev.sunbirdrc.pojos.attestation.exception.PolicyNotFoundException;
import dev.sunbirdrc.registry.entities.AttestationPolicy;
import dev.sunbirdrc.registry.exception.SignatureException;
import dev.sunbirdrc.registry.exception.UnAuthorizedException;
import dev.sunbirdrc.registry.middleware.MiddlewareHaltException;
import dev.sunbirdrc.registry.middleware.service.ConditionResolverService;
import dev.sunbirdrc.registry.middleware.util.JSONUtil;
import dev.sunbirdrc.registry.middleware.util.OSSystemFields;
import dev.sunbirdrc.registry.model.DBConnectionInfoMgr;
import dev.sunbirdrc.registry.model.attestation.EntityPropertyURI;
import dev.sunbirdrc.registry.model.dto.AttestationRequest;
import dev.sunbirdrc.registry.service.*;
import dev.sunbirdrc.registry.sink.shard.Shard;
import dev.sunbirdrc.registry.sink.shard.ShardManager;
import dev.sunbirdrc.registry.util.*;
import dev.sunbirdrc.validators.IValidate;
import dev.sunbirdrc.views.ViewTemplate;
import dev.sunbirdrc.views.ViewTransformer;
import io.minio.errors.*;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.keycloak.adapters.springsecurity.token.KeycloakAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static dev.sunbirdrc.pojos.attestation.Action.GRANT_CLAIM;
import static dev.sunbirdrc.registry.Constants.*;
import static dev.sunbirdrc.registry.exception.ErrorMessages.*;
import static dev.sunbirdrc.registry.middleware.util.Constants.*;
import static dev.sunbirdrc.registry.middleware.util.OSSystemFields.*;

/**
 * This is helper class, user-service calls this class in-order to access registry functionality
 */
@Component
@Setter
public class RegistryHelper {

    private static final String ATTESTED_DATA = "attestedData";
    private static final String CLAIM_ID = "claimId";
    public static String ROLE_ANONYMOUS = "anonymous";

    private static final Logger logger = LoggerFactory.getLogger(RegistryHelper.class);

    @Value("${authentication.enabled:true}") boolean securityEnabled;
    @Value("${notification.service.enabled}") boolean notificationEnabled;

    @Autowired
    private ShardManager shardManager;

    @Autowired
    RegistryService registryService;

    @Autowired
    IReadService readService;

    @Autowired
    IValidate validationService;

    @Autowired
    private ISearchService searchService;

    @Autowired
    private ViewTemplateManager viewTemplateManager;

    @Autowired
    EntityStateHelper entityStateHelper;

    @Autowired
    private DefinitionsManager definitionsManager;

    @Autowired
    private DBConnectionInfoMgr dbConnectionInfoMgr;

    @Autowired
    private DecryptionHelper decryptionHelper;

    @Autowired
    private SunbirdRCInstrumentation watch;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FileStorageService fileStorageService;

    @Value("${database.uuidPropertyName}")
    public String uuidPropertyName;

    @Value("${audit.frame.suffix}")
    public String auditSuffix;

    @Value("${audit.frame.suffixSeparator}")
    public String auditSuffixSeparator;

    @Value("${conditionalAccess.internal}")
    private String internalFieldsProp;

    @Value("${conditionalAccess.private}")
    private String privateFieldsProp;

    @Value("${signature.enabled}")
    private boolean signatureEnabled;

    @Value("${workflow.enabled:true}")
    private boolean workflowEnabled;

    @Autowired
    private EntityTypeHandler entityTypeHandler;

    @Autowired
    private SignatureService signatureService;

    @Autowired
    private ConditionResolverService conditionResolverService;

    public JsonNode removeFormatAttr(JsonNode requestBody) {
        String documents = "documents";
        if (requestBody.has(documents)) {
            JsonNode documentsNode = requestBody.get(documents);
            String format = "format";
            JSONUtil.removeNodes(documentsNode, Collections.singletonList(format));
            ObjectNode node = (ObjectNode) requestBody;
            node.set(documents, documentsNode);
            return node;
        }
        return requestBody;
    }

    /**
     * calls validation and then persists the record to registry.
     *
     * @param inputJson
     * @return
     * @throws Exception
     */
    public String addEntity(JsonNode inputJson, String userId) throws Exception {
        return addEntityHandler(inputJson, userId, false);
    }

    public String inviteEntity(JsonNode inputJson, String userId) throws Exception {
        String entityId = addEntityHandler(inputJson, userId, true);
        sendInviteNotification(inputJson);
        return entityId;
    }

    private String addEntityWithoutValidation(JsonNode inputJson, String userId, String entityName) throws Exception {
        return addEntity(inputJson, userId, entityName, true);
    }

    private String addEntityHandler(JsonNode inputJson, String userId, boolean isInvite) throws Exception {
        String entityType = inputJson.fields().next().getKey();
        validationService.validate(entityType, objectMapper.writeValueAsString(inputJson), isInvite);
        String entityName = inputJson.fields().next().getKey();
        if (workflowEnabled) {
            List<AttestationPolicy> attestationPolicies = getAttestationPolicies(entityName);
            entityStateHelper.applyWorkflowTransitions(JSONUtil.convertStringJsonNode("{}"), inputJson, attestationPolicies);
        }
        if (!StringUtils.isEmpty(userId)) {
            ArrayNode jsonNode = (ArrayNode) inputJson.get(entityName).get(osOwner.toString());
            if (jsonNode == null) {
                jsonNode = new ObjectMapper().createArrayNode();
                ((ObjectNode) inputJson.get(entityName)).set(osOwner.toString(), jsonNode);
            }
            jsonNode.add(userId);
        }
        return addEntity(inputJson, userId, entityType, isInvite);
    }

    private void sendInviteNotification(JsonNode inputJson) throws Exception {
        String entityType = inputJson.fields().next().getKey();
        sendNotificationToOwners(inputJson, INVITE, String.format(INVITE_SUBJECT_TEMPLATE, entityType), String.format(INVITE_BODY_TEMPLATE, entityType));
    }

    private void sendNotificationToOwners(JsonNode inputJson, String operation, String subject, String message) throws Exception {
        if (notificationEnabled) {
            String entityType = inputJson.fields().next().getKey();
            for (ObjectNode owners : entityStateHelper.getOwnersData(inputJson, entityType)) {
                String ownerMobile = owners.get(MOBILE).asText("");
                String ownerEmail = owners.get(EMAIL).asText("");
                if (!StringUtils.isEmpty(ownerMobile)) {
                    registryService.callNotificationActors(operation, String.format("tel:%s", ownerMobile), subject, message);
                }
                if (!StringUtils.isEmpty(ownerEmail)) {
                    registryService.callNotificationActors(operation, String.format("mailto:%s", ownerEmail), subject, message);
                }
            }
        }
    }

    private String addEntity(JsonNode inputJson, String userId, String entityType, boolean skipSignature) throws Exception {
        RecordIdentifier recordId = null;
        try {
            logger.info("Add api: entity type: {} and shard propery: {}", entityType, shardManager.getShardProperty());
            Shard shard = shardManager.getShard(inputJson.get(entityType).get(shardManager.getShardProperty()));
            watch.start("RegistryController.addToExistingEntity");
            String resultId = registryService.addEntity(shard, userId, inputJson, skipSignature);
            recordId = new RecordIdentifier(shard.getShardLabel(), resultId);
            watch.stop("RegistryController.addToExistingEntity");
            logger.info("AddEntity,{}", recordId.toString());
        } catch (Exception e) {
            logger.error("Exception in controller while adding entity !", e);
            throw new Exception(e);
        }
        return recordId.toString();
    }

    /**
     * Get entity details from the DB and modifies data according to view template
     *
     * @param inputJson
     * @param requireLDResponse
     * @return
     * @throws Exception
     */
    public JsonNode readEntity(JsonNode inputJson, String userId, boolean requireLDResponse) throws Exception {
        logger.debug("readEntity starts");
        String entityType = inputJson.fields().next().getKey();
        String label = inputJson.get(entityType).get(dbConnectionInfoMgr.getUuidPropertyName()).asText();
        boolean includeSignatures = inputJson.get(entityType).get("includeSignatures") != null;

        return readEntity(userId, entityType, label, includeSignatures, viewTemplateManager.getViewTemplate(inputJson), requireLDResponse);

    }

    public JsonNode readEntity(String userId, String entityType, String label, boolean includeSignatures, ViewTemplate viewTemplate, boolean requireLDResponse) throws Exception {
        boolean includePrivateFields = false;
        JsonNode resultNode = null;
        RecordIdentifier recordId = RecordIdentifier.parse(label);
        String shardId = dbConnectionInfoMgr.getShardId(recordId.getShardLabel());
        Shard shard = shardManager.activateShard(shardId);
        logger.info("Read Api: shard id: " + recordId.getShardLabel() + " for label: " + label);
        ReadConfigurator configurator = ReadConfiguratorFactory.getOne(includeSignatures);
        configurator.setIncludeTypeAttributes(requireLDResponse);
        if (viewTemplate != null) {
            includePrivateFields = viewTemplateManager.isPrivateFieldEnabled(viewTemplate, entityType);
        }
        configurator.setIncludeEncryptedProp(includePrivateFields);
        resultNode = readService.getEntity(shard, userId, recordId.getUuid(), entityType, configurator);
        if (!isOwner(resultNode.get(entityType), userId)) {
//            throw new Exception("Unauthorized");
            //TODO: return public fields
        }
        if (viewTemplate != null) {
            ViewTransformer vTransformer = new ViewTransformer();
            resultNode = includePrivateFields ? decryptionHelper.getDecryptedJson(resultNode) : resultNode;
            resultNode = vTransformer.transform(viewTemplate, resultNode);
        }
        logger.debug("readEntity ends");
        return resultNode;
    }

    private boolean isOwner(JsonNode entity, String userId) {
        String osOwner = OSSystemFields.osOwner.toString();
        return userId != null && (!entity.has(osOwner) || entity.get(osOwner).toString().contains(userId));
    }

    /**
     * Get entity details from the DB and modifies data according to view template, requests which need only json format can call this method
     *
     * @param inputJson
     * @return
     * @throws Exception
     */
    public JsonNode readEntity(JsonNode inputJson, String userId) throws Exception {
        return readEntity(inputJson, userId, false);
    }

    /**
     * Search the input in the configured backend, external api's can use this method for searching
     *
     * @param inputJson
     * @return
     * @throws Exception
     */
    public JsonNode searchEntity(JsonNode inputJson) throws Exception {
        logger.debug("searchEntity starts");
        JsonNode resultNode = searchService.search(inputJson);
        removeNonPublicFields((ObjectNode) resultNode);
        ViewTemplate viewTemplate = viewTemplateManager.getViewTemplate(inputJson);
        if (viewTemplate != null) {
            ViewTransformer vTransformer = new ViewTransformer();
            resultNode = vTransformer.transform(viewTemplate, resultNode);
        }
        // Search is tricky to support LD. Needs a revisit here.
        logger.debug("searchEntity ends");
        return resultNode;
    }

    private void removeNonPublicFields(ObjectNode searchResultNode) throws Exception {
        if (searchResultNode != null) {
            ObjectReader stringListReader = objectMapper.readerFor(new TypeReference<List<String>>() {
            });
            List<String> nonPublicNodePathContainers = Arrays.asList(internalFieldsProp, privateFieldsProp);
            Iterator<Map.Entry<String, JsonNode>> fieldIterator = searchResultNode.fields();
            while (fieldIterator.hasNext()) {
                ArrayNode entityResults = (ArrayNode) fieldIterator.next().getValue();
                for (int i = 0; i < entityResults.size(); i++) {
                    ObjectNode entityResult = (ObjectNode) entityResults.get(i);
                    List<String> nodePathsForRemoval = new ArrayList<>();
                    for (String nodePathContainer : nonPublicNodePathContainers) {
                        if (entityResult.has(nodePathContainer)) {
                            nodePathsForRemoval.addAll(stringListReader.readValue(entityResult.get(nodePathContainer)));
                        }
                    }
                    JSONUtil.removeNodesByPath(entityResult, nodePathsForRemoval);
                    entityResult.remove(nonPublicNodePathContainers);
                }
            }
        }
    }

    /**
     * Updates the input entity, external api's can use this method to update the entity
     *
     * @param inputJson
     * @param userId
     * @throws Exception
     */
    private void updateEntity(JsonNode inputJson, String userId) throws Exception {
        logger.debug("updateEntity starts");
        String entityType = inputJson.fields().next().getKey();
        String jsonString = objectMapper.writeValueAsString(inputJson);
        validationService.validate(entityType, jsonString, true);
        Shard shard = shardManager.getShard(inputJson.get(entityType).get(shardManager.getShardProperty()));
        String label = inputJson.get(entityType).get(dbConnectionInfoMgr.getUuidPropertyName()).asText();
        RecordIdentifier recordId = RecordIdentifier.parse(label);
        logger.info("Update Api: shard id: " + recordId.getShardLabel() + " for uuid: " + recordId.getUuid());
        registryService.updateEntity(shard, userId, recordId.getUuid(), jsonString);
        logger.debug("updateEntity ends");
    }

    public String updateProperty(JsonNode inputJson, String userId) throws Exception {
        logger.debug("updateEntity starts");
        String entityType = inputJson.fields().next().getKey();
        String jsonString = objectMapper.writeValueAsString(inputJson);
        Shard shard = shardManager.getShard(inputJson.get(entityType).get(shardManager.getShardProperty()));
        String label = inputJson.get(entityType).get(dbConnectionInfoMgr.getUuidPropertyName()).asText();
        RecordIdentifier recordId = RecordIdentifier.parse(label);
        logger.info("Update Api: shard id: " + recordId.getShardLabel() + " for uuid: " + recordId.getUuid());
        registryService.updateEntity(shard, userId, recordId.getUuid(), jsonString);
        return "SUCCESS";
    }

    public void updateEntityAndState(JsonNode inputJson, String userId) throws Exception {
        JsonNode existingNode = readEntity(inputJson, userId);
        updateEntityAndState(existingNode, inputJson, userId);
    }

    private void updateEntityAndState(JsonNode existingNode, JsonNode updatedNode, String userId) throws Exception {
        if (workflowEnabled) {
            String entityName = updatedNode.fields().next().getKey();
            List<AttestationPolicy> attestationPolicies = getAttestationPolicies(entityName);
            entityStateHelper.applyWorkflowTransitions(existingNode, updatedNode, attestationPolicies);
        }
        updateEntity(updatedNode, userId);
    }

    public void addEntityProperty(String entityName, String entityId, JsonNode inputJson, HttpServletRequest request) throws Exception {
        String propertyURI = getPropertyURI(entityId, request);
        JsonNode existingNode = readEntity("", entityName, entityId, false, null, false);
        JsonNode updateNode = existingNode.deepCopy();

        JsonPointer propertyURIPointer = JsonPointer.compile("/" + propertyURI);
        String propertyName = propertyURIPointer.last().getMatchingProperty();
        String parentURIPointer = propertyURIPointer.head().toString();

        JsonNode parentNode = getParentNode(entityName, updateNode, parentURIPointer);
        JsonNode propertyNode = parentNode.get(propertyName);

        createOrUpdateProperty(entityName, inputJson, updateNode, propertyName, (ObjectNode) parentNode, propertyNode);
        updateEntityAndState(existingNode, updateNode, "");
    }

    public String triggerAttestation(AttestationRequest attestationRequest, AttestationPolicy attestationPolicy) throws Exception {
        addAttestationProperty(attestationRequest);
        //TODO: remove reading the entity after update
        String attestationOSID = getAttestationOSID(attestationRequest);

        String condition = "";
        if (attestationPolicy.isInternal()) {
            // Resolve condition for REQUESTER
            condition = conditionResolverService.resolve(attestationRequest.getPropertyData(), REQUESTER,
                    attestationPolicy.getConditions(), Collections.emptyList());
        }

        updateGetFileUrl(attestationRequest.getAdditionalInput());

        PluginRequestMessage message = PluginRequestMessageCreator.create(
                attestationRequest.getPropertyData().toString(), condition, attestationOSID, attestationRequest.getEntityName(),
                attestationRequest.getEmailId(), attestationRequest.getEntityId(), attestationRequest.getAdditionalInput(),
                Action.RAISE_CLAIM.name(), attestationPolicy.getName(), attestationPolicy.getAttestorPlugin(),
                attestationPolicy.getAttestorEntity(), attestationPolicy.getAttestorSignin(),
                attestationRequest.getPropertiesOSID(), attestationRequest.getEmailId());

        PluginRouter.route(message);
        return attestationOSID;
    }


    private void updateGetFileUrl(JsonNode additionalInput) {
        if(additionalInput!= null && additionalInput.has(FILE_URL)) {
            ArrayNode fileUrls = (ArrayNode)(additionalInput.get(FILE_URL));
            ArrayNode signedUrls = JsonNodeFactory.instance.arrayNode();
            for (JsonNode fileNode : fileUrls) {
                String fileUrl = fileNode.asText();
                try {
                    String sharableUrl = fileStorageService.getSignedUrl(fileUrl);
                    signedUrls.add(sharableUrl);
                } catch (ServerException | InternalException | XmlParserException | InvalidResponseException
                         | InvalidKeyException | NoSuchAlgorithmException | IOException
                         | ErrorResponseException | InsufficientDataException e) {
                    e.printStackTrace();
                }
            }
            ((ObjectNode)additionalInput).replace(FILE_URL, signedUrls);
        }
    }

    private String getAttestationOSID(AttestationRequest attestationRequest) throws Exception {
        JsonNode resultNode = readEntity("", attestationRequest.getEntityName(), attestationRequest.getEntityId(),
                false, null, false)
                .get(attestationRequest.getEntityName())
                .get(attestationRequest.getName());
        List<String> fieldsToRemove = getFieldsToRemove(attestationRequest.getEntityName());
        return JSONUtil.getOSIDFromArrNode(resultNode, JSONUtil.convertObjectJsonNode(attestationRequest), fieldsToRemove);
    }

    private void addAttestationProperty(AttestationRequest attestationRequest) throws Exception {
        JsonNode existingEntityNode = readEntity(attestationRequest.getUserId(), attestationRequest.getEntityName(),
                attestationRequest.getEntityId(), false, null, false);
        JsonNode nodeToUpdate = existingEntityNode.deepCopy();
        JsonNode parentNode = nodeToUpdate.get(attestationRequest.getEntityName());
        JsonNode propertyNode = parentNode.get(attestationRequest.getName());
        ObjectNode attestationJsonNode = (ObjectNode) JSONUtil.convertObjectJsonNode(attestationRequest);
        attestationJsonNode.set("propertyData", JsonNodeFactory.instance.textNode(attestationRequest.getPropertyData().toString()));
        createOrUpdateProperty(attestationRequest.getEntityName(), attestationJsonNode, nodeToUpdate, attestationRequest.getName(), (ObjectNode) parentNode, propertyNode);
        updateEntityAndState(existingEntityNode, nodeToUpdate, attestationRequest.getUserId());
    }
    private void createOrUpdateProperty(String entityName, JsonNode inputJson, JsonNode updateNode, String propertyName, ObjectNode parentNode, JsonNode propertyNode) throws JsonProcessingException {
        if (propertyNode != null && !propertyNode.isMissingNode()) {
            updateProperty(inputJson, propertyName, parentNode, propertyNode);
        } else {
            // if array property
            createProperty(entityName, inputJson, updateNode, propertyName, parentNode);
        }
    }

    private void createProperty(String entityName, JsonNode inputJson, JsonNode updateNode, String propertyName, ObjectNode parentNode) throws JsonProcessingException {
        ArrayNode newPropertyNode = objectMapper.createArrayNode().add(inputJson);
        parentNode.set(propertyName, newPropertyNode);
        try {
            validationService.validate(entityName, objectMapper.writeValueAsString(updateNode), false);
        } catch (MiddlewareHaltException me) {
            // try a field node since array validation failed
            parentNode.set(propertyName, inputJson);
        }
    }

    private void updateProperty(JsonNode inputJson, String propertyName, ObjectNode parentNode, JsonNode propertyNode) {
        if (propertyNode.isArray()) {
            ((ArrayNode) propertyNode).add(inputJson);
        } else if (propertyNode.isObject()) {
            inputJson.fields().forEachRemaining(f -> {
                ((ObjectNode) propertyNode).set(f.getKey(), f.getValue());
            });
        } else {
            parentNode.set(propertyName, inputJson);
        }
    }

    private JsonNode getParentNode(String entityName, JsonNode jsonNode, String parentURIPointer) throws Exception {
        JsonNode parentNode;
        if (parentURIPointer.equals("")) {
            parentNode = jsonNode.get(entityName);
        } else {
            Optional<EntityPropertyURI> parentURI = EntityPropertyURI.fromEntityAndPropertyURI(
                    jsonNode.get(entityName),
                    parentURIPointer,
                    uuidPropertyName
            );
            if (!parentURI.isPresent()) {
                throw new Exception(parentURI + " does not exist");
            }
            parentNode = jsonNode.get(entityName).at(parentURI.get().getJsonPointer());
        }
        return parentNode;
    }

    public void updateEntityProperty(String entityName, String entityId, JsonNode inputJson, HttpServletRequest request) throws Exception {
        String propertyURI = getPropertyURI(entityId, request);
        JsonNode existingNode = readEntity("", entityName, entityId, false, null, false);
        JsonNode updateNode = existingNode.deepCopy();

        Optional<EntityPropertyURI> entityPropertyURI = EntityPropertyURI
                .fromEntityAndPropertyURI(updateNode.get(entityName), propertyURI, uuidPropertyName);

        if (!entityPropertyURI.isPresent()) {
            throw new Exception(propertyURI + ": do not exist");
        }

        JsonNode existingPropertyNode = updateNode.get(entityName).at(entityPropertyURI.get().getJsonPointer());
        JsonNode propertyParentNode = updateNode.get(entityName).at(entityPropertyURI.get().getJsonPointer().head());
        String propertyName = entityPropertyURI.get().getJsonPointer().last().getMatchingProperty();

        if (propertyParentNode.isObject()) {
            ((ObjectNode) propertyParentNode).set(propertyName, inputJson);
        } else if (existingPropertyNode.isObject()) {
            inputJson.fields().forEachRemaining(f -> ((ObjectNode) existingPropertyNode).set(f.getKey(), f.getValue()));
        } else {
            int propertyIndex = Integer.parseInt(propertyName);
            ((ArrayNode) propertyParentNode).set(propertyIndex, inputJson);
        }
        updateEntityAndState(existingNode, updateNode, "");

    }

    public void attestEntity(String entityName, JsonNode node, String[] jsonPaths, String userId) throws Exception {
        String patch = String.format("[{\"op\":\"add\", \"path\": \"attested\", \"value\": {\"attestation\":{\"id\":\"%s\"}, \"path\": \"%s\"}}]", userId, jsonPaths[0]);
        JsonPatch.applyInPlace(objectMapper.readTree(patch), node.get(entityName));
        updateEntity(node, userId);
    }

    public void updateState(PluginResponseMessage pluginResponseMessage) throws Exception {
        String attestationName = pluginResponseMessage.getPolicyName();
        String attestationOSID = pluginResponseMessage.getAttestationOSID();
        String sourceEntity = pluginResponseMessage.getSourceEntity();
        AttestationPolicy attestationPolicy = getAttestationPolicy(sourceEntity, attestationName);
        String userId = "";

        JsonNode root = readEntity(userId, sourceEntity, pluginResponseMessage.getSourceOSID(), false, null, false);
        ObjectNode metaData = JsonNodeFactory.instance.objectNode();
        JsonNode additionalData = pluginResponseMessage.getAdditionalData();
        Action action = Action.valueOf(pluginResponseMessage.getStatus());
        switch (action) {
            case GRANT_CLAIM:
                Object credentialTemplate = attestationPolicy.getCredentialTemplate();
                // checking size greater than 1, bcz empty template contains osid field
                if (credentialTemplate != null) {
                    JsonNode response = objectMapper.readTree(pluginResponseMessage.getResponse());
                    Object signedData = getSignedDoc(response, credentialTemplate);
                    metaData.put(
                            ATTESTED_DATA,
                            signedData.toString()
                    );
                } else {
                    metaData.put(
                            ATTESTED_DATA,
                            pluginResponseMessage.getResponse()
                    );
                }
                break;
            case SELF_ATTEST:
                String hashOfTheFile = pluginResponseMessage.getResponse();
                metaData.put(
                        ATTESTED_DATA,
                        hashOfTheFile
                );
                break;
            case RAISE_CLAIM:
                metaData.put(
                        CLAIM_ID,
                        additionalData.get(CLAIM_ID).asText("")
                );
        }
        String propertyURI = attestationName + "/" + attestationOSID;
        uploadAttestedFiles(pluginResponseMessage, metaData);
        JsonNode nodeToUpdate = entityStateHelper.manageState(attestationPolicy, root, propertyURI, action, metaData);
        updateEntity(nodeToUpdate, userId);
        if (action == GRANT_CLAIM && !StringUtils.isEmpty(attestationPolicy.getOnComplete())) {
            try {
                AttestationPolicy nextAttestationPolicy = getAttestationPolicy(sourceEntity, attestationPolicy.getOnComplete());
                AttestationRequest attestationRequest = AttestationRequest.builder().entityName(pluginResponseMessage.getSourceEntity())
                        .entityId(pluginResponseMessage.getSourceOSID()).name(attestationPolicy.getOnComplete())
                        .additionalInput(pluginResponseMessage.getAdditionalData()).emailId(pluginResponseMessage.getEmailId())
                        .userId(pluginResponseMessage.getUserId()).propertiesOSID(pluginResponseMessage.getPropertiesOSID())
                        .propertyData(JSONUtil.convertStringJsonNode(pluginResponseMessage.getResponse())).build();
                triggerAttestation(attestationRequest, nextAttestationPolicy);
            } catch (PolicyNotFoundException e) {
                logger.error("Next level attestation policy not found:", e);
            }
        }
    }

    private void uploadAttestedFiles(PluginResponseMessage pluginResponseMessage, ObjectNode metaData) throws Exception {
        if (!CollectionUtils.isEmpty(pluginResponseMessage.getFiles())) {
            ArrayNode fileUris = JsonNodeFactory.instance.arrayNode();
            pluginResponseMessage.getFiles().forEach(file -> {
                String propertyURI = String.format("%s/%s/%s/documents/%s", pluginResponseMessage.getSourceEntity(),
                        pluginResponseMessage.getSourceOSID(), pluginResponseMessage.getPolicyName(), file.getFileName());
                try {
                    fileStorageService.save(new ByteArrayInputStream(file.getFile()), propertyURI);
                } catch (Exception e) {
                    logger.error("Failed persisting file", e);
                    e.printStackTrace();
                }
                fileUris.add(propertyURI);
            });
            JsonNode jsonNode = metaData.get(ATTESTED_DATA);
            ObjectNode attestedObjectNode = objectMapper.readValue(jsonNode.asText(), ObjectNode.class);
            attestedObjectNode.set("files", fileUris);
            metaData.put(ATTESTED_DATA, attestedObjectNode.toString());
        }
    }

    /**
     * Get Audit log information , external api's can use this method to get the
     * audit log of an antity
     *
     * @param inputJson
     * @return
     * @throws Exception
     */

    public JsonNode getAuditLog(JsonNode inputJson) throws Exception {
        logger.debug("get audit log starts");
        String entityType = inputJson.fields().next().getKey();
        JsonNode queryNode = inputJson.get(entityType);

        ArrayNode newEntityArrNode = objectMapper.createArrayNode();
        newEntityArrNode.add(entityType + auditSuffixSeparator + auditSuffix);
        ((ObjectNode) queryNode).set("entityType", newEntityArrNode);

        JsonNode resultNode = searchService.search(queryNode);

        ViewTemplate viewTemplate = viewTemplateManager.getViewTemplate(inputJson);
        if (viewTemplate != null) {
            ViewTransformer vTransformer = new ViewTransformer();
            resultNode = vTransformer.transform(viewTemplate, resultNode);
        }
        logger.debug("get audit log ends");

        return resultNode;

    }

    public boolean doesEntityContainOwnershipAttributes(@PathVariable String entityName) {
        if (definitionsManager.getDefinition(entityName) != null) {
            return definitionsManager.getDefinition(entityName).getOsSchemaConfiguration().getOwnershipAttributes().size() > 0;
        } else {
            return false;
        }
    }

    public String getUserId(HttpServletRequest request, String entityName) throws Exception {
        if (doesEntityContainOwnershipAttributes(entityName) || getManageRoles(entityName).size() > 0) {
            return fetchUserIdFromToken(request);
        } else {
            return dev.sunbirdrc.registry.Constants.USER_ANONYMOUS;
        }
    }

    private String fetchUserIdFromToken(HttpServletRequest request) throws Exception {
        if(!securityEnabled){
            return DEFAULT_USER;
        }
        return getKeycloakUserId(request);
    }

    public String getKeycloakUserId(HttpServletRequest request) throws Exception {
        KeycloakAuthenticationToken principal = (KeycloakAuthenticationToken) request.getUserPrincipal();
        if (principal != null) {
            return principal.getAccount().getPrincipal().getName();
        }
        throw new Exception("Forbidden");
    }

    public String fetchEmailIdFromToken(HttpServletRequest request, String entityName) throws Exception {
        if (doesEntityContainOwnershipAttributes(entityName) || getManageRoles(entityName).size() > 0) {
            KeycloakAuthenticationToken principal = (KeycloakAuthenticationToken) request.getUserPrincipal();
            if (principal != null) {
                try{
                    return principal.getAccount().getKeycloakSecurityContext().getToken().getEmail();
                }catch (Exception exception){
                    return principal.getAccount().getPrincipal().getName();
                }
            }
            throw new Exception("Forbidden");
        } else {
            return dev.sunbirdrc.registry.Constants.USER_ANONYMOUS;
        }
    }

    public JsonNode getRequestedUserDetails(HttpServletRequest request, String entityName) throws Exception {
        if (isInternalRegistry(entityName)) {
            return getUserInfoFromRegistry(request, entityName);
        } else if (entityTypeHandler.isExternalRegistry(entityName)) {
            return getUserInfoFromKeyCloak(request, entityName);
        }
        throw new Exception(NOT_PART_OF_THE_SYSTEM_EXCEPTION);
    }

    private boolean isInternalRegistry(String entityName) {
        return definitionsManager.getAllKnownDefinitions().contains(entityName);
    }

    private JsonNode getUserInfoFromKeyCloak(HttpServletRequest request, String entityName) {
        Set<String> roles = getUserRolesFromRequest(request);
        JsonNode rolesNode = objectMapper.convertValue(roles, JsonNode.class);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        // To maintain the consistency with searchEntity we are using ArrayNode
        ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
        arrayNode.add(rolesNode);
        result.set(entityName, arrayNode);
        return result;
    }

    private JsonNode getUserInfoFromRegistry(HttpServletRequest request, String entityName) throws Exception {
        String userId = getUserId(request,entityName);
        if (userId != null) {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.set("entityType", JsonNodeFactory.instance.arrayNode().add(entityName));
            ObjectNode filters = JsonNodeFactory.instance.objectNode();
            filters.set(OSSystemFields.osOwner.toString(), JsonNodeFactory.instance.objectNode().put("contains", userId));
            payload.set("filters", filters);

            watch.start("RegistryController.searchEntity");
            JsonNode result = searchEntity(payload);
            watch.stop("RegistryController.searchEntity");
            return result;
        }
        throw new Exception("Forbidden");
    }

    public void authorize(String entityName, String entityId, HttpServletRequest request) throws Exception {
        String userIdFromRequest = getUserId(request, entityName);
        JsonNode response = readEntity(userIdFromRequest, entityName, entityId, false, null, false);
        JsonNode entityFromDB = response.get(entityName);
        if (!isOwner(entityFromDB, userIdFromRequest)) {
            throw new Exception(UNAUTHORIZED_OPERATION_MESSAGE);
        }
    }

    public String getPropertyIdAfterSavingTheProperty(String entityName, String entityId, JsonNode requestBody, HttpServletRequest request) throws Exception {
        JsonNode resultNode = readEntity("", entityName, entityId, false, null, false)
                .get(entityName);
        String propertyURI = getPropertyURI(entityId, request);
        JsonNode jsonNode = resultNode.get(propertyURI);
        List<String> fieldsToRemove = getFieldsToRemove(entityName);
        if (jsonNode.isArray()) {
            ArrayNode arrayNode = (ArrayNode) jsonNode;
            for (JsonNode next : arrayNode) {
                JsonNode existingProperty = next.deepCopy();
                JSONUtil.removeNodes(existingProperty, fieldsToRemove);

                JsonNode requestBodyWithoutSystemFields = requestBody.deepCopy();
                JSONUtil.removeNodes(requestBodyWithoutSystemFields, fieldsToRemove);

                if (existingProperty.equals(requestBodyWithoutSystemFields)) {
                    return next.get(uuidPropertyName).asText();
                }
            }
        }
        return "";
    }

    public String getPropertyURI(String entityId, HttpServletRequest request) {
        return request.getRequestURI().split(entityId + "/")[1];
    }

    @NotNull
    public List<String> getFieldsToRemove(String entityName) {
        List<String> fieldsToRemove = new ArrayList<>();
        fieldsToRemove.add(uuidPropertyName);
        List<String> systemFields = definitionsManager.getDefinition(entityName).getOsSchemaConfiguration().getSystemFields();
        fieldsToRemove.addAll(systemFields);
        return fieldsToRemove;
    }

    public ArrayNode fetchFromDBUsingEsResponse(String entity, ArrayNode esSearchResponse) throws Exception {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode value : esSearchResponse) {
            JsonNode dbResponse = readEntity("", entity, value.get(uuidPropertyName).asText(), false, null, false);
            result.add(dbResponse.get(entity));
        }
        return result;
    }

    public void authorizeInviteEntity(HttpServletRequest request, String entityName) throws Exception {
        List<String> inviteRoles = definitionsManager.getDefinition(entityName)
                .getOsSchemaConfiguration()
                .getInviteRoles();
        if (inviteRoles.contains(ROLE_ANONYMOUS)) {
            return;
        }
        Set<String> userRoles = getUserRolesFromRequest(request);
        authorizeUserRole(userRoles, inviteRoles);
    }

    public void authorizeDeleteEntity(HttpServletRequest request, String entityName,String entityId) throws Exception {
        List<String> deleteRoles = definitionsManager.getDefinition(entityName)
          .getOsSchemaConfiguration()
          .getDeleteRoles();
        if (deleteRoles.contains(ROLE_ANONYMOUS)) {
            return;
        }
        Set<String> userRoles = getUserRolesFromRequest(request);
        String userIdFromRequest = getUserId(request, entityName);
        JsonNode response = readEntity(userIdFromRequest, entityName, entityId, false, null, false);
        JsonNode entityFromDB = response.get(entityName);
        final boolean hasNoValidRole = !deleteRoles.isEmpty() && deleteRoles.stream().noneMatch(userRoles::contains);
        final boolean hasInValidOwnership = !isOwner(entityFromDB, userIdFromRequest);
        if(hasNoValidRole && hasInValidOwnership){
            throw new UnAuthorizedException(UNAUTHORIZED_OPERATION_MESSAGE);
        }
    }

    public String authorizeManageEntity(HttpServletRequest request, String entityName) throws Exception {

        List<String> managingRoles = getManageRoles(entityName);
        if (managingRoles.size() > 0) {
            if (managingRoles.contains(ROLE_ANONYMOUS)) {
                return ROLE_ANONYMOUS;
            }
            Set<String> userRoles = getUserRolesFromRequest(request);
            authorizeUserRole(userRoles, managingRoles);
            return fetchUserIdFromToken(request);
        } else {
            return ROLE_ANONYMOUS;
        }
    }

    private List<String> getManageRoles(String entityName) {
        if (definitionsManager.getDefinition(entityName) != null) {
            return definitionsManager.getDefinition(entityName)
                    .getOsSchemaConfiguration()
                    .getRoles();
        } else {
            return Collections.emptyList();
        }

    }

    private Set<String> getUserRolesFromRequest(HttpServletRequest request) {
        KeycloakAuthenticationToken userPrincipal = (KeycloakAuthenticationToken) request.getUserPrincipal();
        return userPrincipal!=null ? userPrincipal.getAccount().getRoles():Collections.emptySet();
    }

    private void authorizeUserRole(Set<String> userRoles, List<String> allowedRoles) throws Exception {
        if (!allowedRoles.isEmpty() && allowedRoles.stream().noneMatch(userRoles::contains)) {
            throw new UnAuthorizedException(UNAUTHORIZED_OPERATION_MESSAGE);
        }
    }

    public void authorizeAttestor(String entity, HttpServletRequest request) throws Exception {
        List<String> keyCloakEntities = getUserEntities(request);
        Set<String> allTheAttestorEntities = definitionsManager.getDefinition(entity)
                .getOsSchemaConfiguration()
                .getAllTheAttestorEntities();
        if (keyCloakEntities.stream().noneMatch(allTheAttestorEntities::contains)) {
            throw new Exception(UNAUTHORIZED_EXCEPTION_MESSAGE);
        }
    }

    public List<String> getUserEntities(HttpServletRequest request) {
        KeycloakAuthenticationToken principal = (KeycloakAuthenticationToken) request.getUserPrincipal();
        Object customAttributes = principal.getAccount()
                .getKeycloakSecurityContext()
                .getToken()
                .getOtherClaims()
                .get("entity");
        return (List<String>) customAttributes;
    }

    @Async
    public void invalidateAttestation(String entityName, String entityId, String userId, @Nullable String propertyToUpdate) throws Exception {
        JsonNode entity = readEntity(userId, entityName, entityId, false, null, false)
                .get(entityName);
        for (AttestationPolicy attestationPolicy : getAttestationPolicies(entityName)) {
            String policyName = attestationPolicy.getName();

            if (entity.has(policyName) && entity.get(policyName).isArray()) {
                ArrayNode attestations = (ArrayNode) entity.get(policyName);
                updateAttestation(attestations,propertyToUpdate);
            }
        }
        ObjectNode newRoot = JsonNodeFactory.instance.objectNode();
        newRoot.set(entityName, entity);
        updateEntity(newRoot, userId);
    }

    public String getPropertyToUpdate(HttpServletRequest request, String entityId){
        String propertyURI = getPropertyURI(entityId, request);
        return propertyURI.split("/")[0];
    }
    private void updateAttestation(ArrayNode attestations,String propertyToUpdate) {
        for (JsonNode attestation : attestations) {
            if (attestation.get(_osState.name()).asText().equals(States.PUBLISHED.name())
              && !attestation.get("name").asText().equals(propertyToUpdate)
            ){
                ObjectNode propertiesOSID = attestation.get("propertiesOSID").deepCopy();
                JSONUtil.removeNode(propertiesOSID, uuidPropertyName);
                ((ObjectNode) attestation).set(_osState.name(), JsonNodeFactory.instance.textNode(States.INVALID.name()));
            }
        }
    }

    public Object getSignedDoc(JsonNode result, Object credentialTemplate) throws
            SignatureException.CreationException, SignatureException.UnreachableException {
        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("data", result);
        requestBodyMap.put("credentialTemplate", credentialTemplate);
        return signatureService.sign(requestBodyMap);
    }

    // TODO: can be async?
    public void signDocument(String entityName, String entityId, String userId) throws Exception {
        if (!signatureEnabled) {
            return;
        }
        Object credentialTemplate = definitionsManager.getCredentialTemplate(entityName);
        if (credentialTemplate != null) {
            ObjectNode updatedNode = (ObjectNode) readEntity(userId, entityName, entityId, false, null, false)
                    .get(entityName);
            Object signedCredentials = getSignedDoc(updatedNode, credentialTemplate);
            updatedNode.set(OSSystemFields._osSignedData.name(), JsonNodeFactory.instance.textNode(signedCredentials.toString()));
            ObjectNode updatedNodeParent = JsonNodeFactory.instance.objectNode();
            updatedNodeParent.set(entityName, updatedNode);
            updateProperty(updatedNodeParent, userId);
        }
    }

    public void deleteEntity(String entityId, String userId) throws Exception {
        RecordIdentifier recordId = RecordIdentifier.parse(entityId);
        String shardId = dbConnectionInfoMgr.getShardId(recordId.getShardLabel());
        Shard shard = shardManager.activateShard(shardId);
        registryService.deleteEntityById(shard, userId, recordId.getUuid());
    }

    //TODO: add cache
    public List<AttestationPolicy> getAttestationPolicies(String entityName) {
        List<AttestationPolicy> dbAttestationPolicies = getAttestationsFromRegistry(entityName);
        List<AttestationPolicy> schemaAttestationPolicies = definitionsManager.getDefinition(entityName).getOsSchemaConfiguration().getAttestationPolicies();
        return ListUtils.union(dbAttestationPolicies, schemaAttestationPolicies);
    }

    private List<AttestationPolicy> getAttestationsFromRegistry(String entityName) {
        try {
            JsonNode searchRequest = objectMapper.readTree("{\n" +
                    "    \"entityType\": [\n" +
                    "        \"" + ATTESTATION_POLICY + "\"\n" +
                    "    ],\n" +
                    "    \"filters\": {\n" +
                    "       \"entity\": {\n" +
                    "           \"eq\": \"" + entityName + "\"\n" +
                    "       }\n" +
                    "    }\n" +
                    "}");
            JsonNode searchResponse = searchEntity(searchRequest);
            return convertJsonNodeToAttestationList(searchResponse);
        } catch (Exception e) {
            logger.error("Error fetching attestation policy", e);
            return Collections.emptyList();
        }
    }

    private List<AttestationPolicy> convertJsonNodeToAttestationList(JsonNode searchResponse) throws java.io.IOException {
        TypeReference<List<AttestationPolicy>> typeRef
                = new TypeReference<List<AttestationPolicy>>() {
        };
        ObjectReader reader = objectMapper.readerFor(typeRef);
        return reader.readValue(searchResponse.get(ATTESTATION_POLICY));
    }

    public boolean isAttestationPolicyNameAlreadyUsed(String entityName, String policyName) {
        List<AttestationPolicy> schemaAttestationPolicies = getAttestationPolicies(entityName);
        return schemaAttestationPolicies.stream().anyMatch(policy -> policy.getName().equals(policyName));
    }

    public AttestationPolicy getAttestationPolicy(String entityName, String policyName) {
        List<AttestationPolicy> attestationPolicies = getAttestationPolicies(entityName);
        return attestationPolicies.stream()
                .filter(policy -> policy.getName().equals(policyName))
                .findFirst()
                .orElseThrow(() -> new PolicyNotFoundException("Policy " + policyName + " is not found"));
    }

    public String createAttestationPolicy(AttestationPolicy attestationPolicy, String userId) throws Exception {
        ObjectNode entity = createJsonNodeForAttestationPolicy(attestationPolicy);
        return addEntityWithoutValidation(entity, userId, ATTESTATION_POLICY);
    }

    private ObjectNode createJsonNodeForAttestationPolicy(AttestationPolicy attestationPolicy) {
        JsonNode inputJson = objectMapper.valueToTree(attestationPolicy);
        ObjectNode entity = JsonNodeFactory.instance.objectNode();
        entity.set(ATTESTATION_POLICY, inputJson);
        return entity;
    }

    public List<AttestationPolicy> findAttestationPolicyByEntityAndCreatedBy(String entityName, String userId) throws Exception {
        JsonNode searchRequest = objectMapper.readTree("{\n" +
                "    \"entityType\": [\n" +
                "        \"" + "ATTESTATION_POLICY" + "\"\n" +
                "    ],\n" +
                "    \"filters\": {\n" +
                "       \"entity\": {\n" +
                "           \"eq\": \"" + entityName + "\"\n" +
                "       },\n" +
                "       \"createdBy\": {\n" +
                "           \"eq\": \"" + userId + "\"\n" +
                "       }\n" +
                "    }\n" +
                "}");
        searchEntity(searchRequest);
        return Collections.emptyList();
    }

    public String updateAttestationPolicy(String userId, AttestationPolicy attestationPolicy) throws Exception {
        JsonNode updateJson = createJsonNodeForAttestationPolicy(attestationPolicy);
        return updateProperty(updateJson, userId);
    }

    public Optional<AttestationPolicy> findAttestationPolicyById(String userId, String policyOSID) throws Exception {
        JsonNode jsonNode = readEntity(userId, ATTESTATION_POLICY, policyOSID, false, null, false)
                .get(ATTESTATION_POLICY);
        return Optional.of(objectMapper.treeToValue(jsonNode, AttestationPolicy.class));
    }


    public void deleteAttestationPolicy(AttestationPolicy attestationPolicy) throws Exception {
        deleteEntity(attestationPolicy.getOsid(), attestationPolicy.getCreatedBy());
    }
}
