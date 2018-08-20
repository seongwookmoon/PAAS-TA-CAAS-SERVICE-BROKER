package org.openpaas.servicebroker.kubernetes.service.impl;

import java.util.HashMap;
import java.util.Map;

import org.openpaas.servicebroker.kubernetes.config.EnvConfig;
import org.openpaas.servicebroker.kubernetes.exception.KubernetesServiceException;
import org.openpaas.servicebroker.kubernetes.model.JpaServiceInstance;
import org.openpaas.servicebroker.kubernetes.service.RestTemplateService;
import org.openpaas.servicebroker.kubernetes.service.TemplateService;
import org.openpaas.servicebroker.model.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * 이 서비스 브로커에서 접근하는 Kubernetes에 대한 서비스를 위한 클래스이다.
 *
 * @author hyerin
 * @author Hyungu Cho
 * @since 2018/07/24
 * @version 20180801
 */
@Service
public class KubernetesService {

	private static final Logger logger = LoggerFactory.getLogger(KubernetesService.class);

	@Autowired
	private TemplateService templateService;

	@Autowired
	private EnvConfig envConfig;

	@Autowired
	private RestTemplateService restTemplateService;

	/**
	 * 1. namespace 생성  
	 * 2. namespace에 quota 할당  
	 * 3. namespace의 user 생성 
	 * 4. 생성한 user에 role 할당 및 binding 
	 * 5. JPA instance 저장용 값 세팅 (spaceName, userName, token)
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public JpaServiceInstance createNamespaceUser(JpaServiceInstance instance, Plan plan) {
		logger.info("In createNamespaceUser !!!");

		String spaceName = createNamespace(instance.getServiceInstanceId());
		this.createResourceQuota(spaceName, plan);
		
		String tmpString[] = instance.getParameter("owner").split("@");
		String userName = (instance.getOrganizationGuid() + "-" + tmpString[0].replaceAll("([:.#$&!_\\(\\)`*%^~,\\<\\>\\[\\];+|-])+", "")).toLowerCase() + "-admin";
		createUser(spaceName, userName);

		createRole(spaceName, userName);
		createRoleBinding(spaceName, userName);

		logger.info("work done!!! {}  {}", spaceName, userName);

		// DB저장을 위한 JPAServiceInstance 리턴
		instance.setCaasNamespace(spaceName);
		instance.setCaasAccountName(userName);
		instance.setCaasAccountAccessToken(getToken(spaceName, userName));
		return instance;

	}

	/**
	 * spacename은 serviceInstance ID 에 앞에는 paas- 뒤에는 -caas를 붙인다. instance/create_namespace.ftl의
	 * 변수를 채운 후 restTemplateService로 rest 통신한다.
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public String createNamespace(String serviceInstanceId) {
		logger.debug("create namespace!!! {}", serviceInstanceId);

		Map<String, Object> model = new HashMap<>();
		String spaceName = "paas-" + serviceInstanceId.toLowerCase() + "-caas";
		model.put("name", spaceName);
		String yml = null;
		try {
			yml = templateService.convert("instance/create_namespace.ftl", model);
		} catch (KubernetesServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		logger.debug("Here is your yml file!!! {}", yml);

		restTemplateService.send(envConfig.getCaasUrl() + "/api/v1/namespaces", yml, HttpMethod.POST, String.class);

		return spaceName;
	}

	/**
	 * namespace에 quota를 할당한다. quota는 plan 정보대로 할당한다. plan정보의 B -> i 로 바꾼 후에
	 * instance/create_resource_quota.ftl의 변수를 채운 후 restTemplateService로 rest 통신한다.
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public void createResourceQuota(String spaceName, Plan plan) {
		logger.info("createUser Resource Quota ~~ space : {}   {}", spaceName, plan);

		Map<String, Object> model = new HashMap<>();
		plan.setMemory(plan.getMemory().replace("B", "i"));
		plan.setDisk(plan.getDisk().replace("B", "i"));
		model.put("quotaName", spaceName + "-resourcequota");
		model.put("plan", plan);
		String yml = null;
		try {
			yml = templateService.convert("instance/create_resource_quota.ftl", model);
		} catch (KubernetesServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		restTemplateService.send(envConfig.getCaasUrl() + "/api/v1/namespaces/" + spaceName + "/resourcequotas", yml, HttpMethod.POST, String.class);

	}

	/**
	 * parameter에 넘어온 userName 값으로 생선된 namespace의 관리자 계정을 생성한다. user명은 web 등에서
	 * kubernetes 명명규칙에 맞는 변수가 넘어왔다고 가정한다. instance/create_account.ftl의 변수를 채운 후
	 * restTemplateService로 rest 통신한다.
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public void createUser(String spaceName, String userName) {
		logger.info("createUser Account~~ {}", userName);

		Map<String, Object> model = new HashMap<>();
		model.put("spaceName", spaceName);
		model.put("userName", userName);
		String yml = null;
		try {
			yml = templateService.convert("instance/create_account.ftl", model);
		} catch (KubernetesServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		restTemplateService.send(envConfig.getCaasUrl() + "/api/v1/namespaces/" + spaceName + "/serviceaccounts", yml, HttpMethod.POST, String.class);
		logger.info("created Account~~ {}", userName);
		
	}
	
	/**
	 * 생성한 유저의 토큰값을 가져온다. 
	 * JSON형태로 값이 넘어오니까 파싱하는 로직이 포함되어 있다.
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public String getToken(String spaceName, String userName) {
		
		String jsonObj = restTemplateService.send(envConfig.getCaasUrl() + "/api/v1/namespaces/" + spaceName + "/serviceaccounts/" + userName, HttpMethod.GET, String.class);
		JsonParser parser = new JsonParser();
		JsonElement element = parser.parse(jsonObj);
		element = element.getAsJsonObject().get("secrets");
		element = element.getAsJsonArray().get(0);
		String token = element.getAsJsonObject().get("name").toString();

		return token;
	}

	/**
	 * 생성된 namespace에 role을 생성한다. role이름은 'namespace명-role' 이다.
	 * instance/create_role.ftl의 변수를 채운 후 restTemplateService로 rest 통신한다.
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public void createRole(String spaceName, String userName) {
		logger.info("create Role And Binding~~ {}", userName);

		Map<String, Object> model = new HashMap<>();
		model.put("spaceName", spaceName);
		model.put("userName", userName);
		model.put("roleName", spaceName + "-role");
		String yml = null;
		try {
			yml = templateService.convert("instance/create_role.ftl", model);
		} catch (KubernetesServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		restTemplateService.send(envConfig.getCaasUrl() + "/apis/rbac.authorization.k8s.io/v1/namespaces/" + spaceName + "/roles", yml, HttpMethod.POST, String.class);

	}

	/**
	 * 생성된 namespace에 roleBinding을 생성한다. binding이름은 'namespace명-role-binding' 이다.
	 * instance/create_roleBinding.ftl의 변수를 채운 후 restTemplateService로 rest 통신한다.
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public void createRoleBinding(String spaceName, String userName) {
		logger.info("create Binding {}", userName);

		Map<String, Object> model = new HashMap<>();
		model.put("spaceName", spaceName);
		model.put("userName", userName);
		model.put("roleName", spaceName + "-role");
		String yml = null;
		try {
			yml = templateService.convert("instance/create_roleBinding.ftl", model);
		} catch (KubernetesServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		restTemplateService.send(envConfig.getCaasUrl() + "/apis/rbac.authorization.k8s.io/v1/namespaces/" + spaceName + "/rolebindings", yml, HttpMethod.POST, String.class);

	}

	/**
	 * namespace를 삭제한다. 삭제를 신청하는 사람이 관리자일 것이라 생각하여, 중복체크, 공유유저 삭제 등의 행동은 하지 않는다.
	 *
	 * @author Hyerin
	 * @since 2018.07.30
	 */
	public void deleteNamespace(String namespace) {
		logger.info("Start to delete namespace in kubernetes.");

		// TODO kubernetes에 있는 namespace 삭제

		restTemplateService.send(envConfig.getCaasUrl() + "/api/v1/namespaces/" + namespace, HttpMethod.DELETE,	String.class);

		logger.info("Done to delete namespace in kubernetes.");

	}

	/**
	 * Namespace가 존재하는지 확인한다. restTemplateService으로 통신시, 있으면 200 OK, 없으면 404 Error를 뿜기 때문에
	 * 에러가 생김 == 해당이름의 namespace가 없음이다.
	 * 
	 * @param namespace
	 * @return
	 */
	public boolean existsNamespace(String namespace) {

		try {
			restTemplateService.send(envConfig.getCaasUrl() + "/api/v1/namespaces/" + namespace, HttpMethod.GET, String.class);
		} catch (HttpStatusCodeException exception) {
			logger.info("can't find namespace {} {} ", exception.getStatusCode().value(), exception.getMessage());
			return false;
		}
		return true;
	}

	/**
	 * namespace에 quota를 재할당한다. 하위->상위 플랜 유효성 확인은 InstanceServiceImpl에서 대신 수행한다.
	 * plan정보의 B -> i 로 바꾼 후에 instance/change_resource_quota.ftl의 변수를 채운 후
	 * restTemplateService로 rest 통신한다.
	 *
	 * @author Hyerin
	 * @author Hyungu Cho
	 * @since 2018.07.30
	 */
	public void changeResourceQuota(String spaceName, Plan plan) {
		logger.info("changeUser Resource Quota ~~ space : {}   {}", spaceName, plan);

		Map<String, Object> model = new HashMap<>();
		plan.setMemory(plan.getMemory().replace("B", "i"));
		plan.setDisk(plan.getDisk().replace("B", "i"));
		model.put("quotaName", spaceName + "-resourcequota");
		model.put("plan", plan);
		String yml = null;
		try {
			yml = templateService.convert("instance/create_resource_quota.ftl", model);
		} catch (KubernetesServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// ResourceQuota Create-POST / Replace-PUT
		String responseBody = restTemplateService.send(envConfig.getCaasUrl() + "/api/v1/namespaces/" + spaceName + "/resourcequotas/" + spaceName + "-resourcequota", yml, HttpMethod.PUT, String.class);

		if (null != responseBody)
			logger.debug("Change ResourceQuota response body : {}", responseBody);
	}
}
