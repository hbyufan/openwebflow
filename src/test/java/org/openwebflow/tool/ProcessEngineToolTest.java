package org.openwebflow.tool;

import java.util.List;

import org.activiti.engine.ProcessEngine;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.TaskService;
import org.activiti.engine.impl.persistence.entity.TaskEntity;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.IdentityLink;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openwebflow.conf.ProcessEngineConfigurationEx;
import org.openwebflow.conf.ReplaceTaskAssignmentManager;
import org.openwebflow.identity.impl.InMemoryMembershipStore;
import org.openwebflow.identity.impl.InMemoryUserDetailsStore;
import org.openwebflow.identity.impl.MyUserDetails;
import org.openwebflow.permission.delegation.InMemoryDelegationDetailsStore;
import org.openwebflow.permission.delegation.TaskDelagation;
import org.openwebflow.permission.list.InMemoryTaskAssignementEntryStore;
import org.openwebflow.util.ModelUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class ProcessEngineToolTest
{
	ProcessEngineTool _tool;

	ProcessEngine _processEngine;

	ApplicationContext _ctx;

	@Before
	public void setUp() throws Exception
	{
		_ctx = new ClassPathXmlApplicationContext("classpath:activiti.cfg.xml");
		_tool = _ctx.getBean(ProcessEngineTool.class);
		Assert.assertNotNull(_tool);
		_processEngine = _tool.getProcessEngine();
	}

	@After
	public void tearDown() throws Exception
	{
	}

	@Test
	public void test() throws Exception
	{
		InMemoryMembershipStore myMembershipManager = _ctx.getBean(InMemoryMembershipStore.class);
		InMemoryTaskAssignementEntryStore myActivityPermissionManager = _ctx
				.getBean(InMemoryTaskAssignementEntryStore.class);

		//设置用户
		myMembershipManager.createGroup("management", "管理员");
		myMembershipManager.createGroup("sales", "销售");
		myMembershipManager.createGroup("engineering", "工程师");

		myMembershipManager.createMembership("bluejoe", "engineering");
		myMembershipManager.createMembership("gonzo", "sales");
		myMembershipManager.createMembership("kermit", "management");

		//设置用户email信息
		InMemoryUserDetailsStore userDetailsStore = _ctx.getBean(InMemoryUserDetailsStore.class);
		userDetailsStore.add(new MyUserDetails("bluejoe", "白乔", "bluejoe2008@gmail.com", "13800138000"));

		// 取得 Activiti 服务
		RepositoryService repositoryService = _processEngine.getRepositoryService();
		Model model = repositoryService.createModelQuery().modelKey("test.bpmn").singleResult();
		Assert.assertNotNull(model);
		Assert.assertEquals(0, repositoryService.createProcessDefinitionQuery().list().size());

		//部署该model
		ModelUtils.deployModel(repositoryService, model.getId());
		Assert.assertEquals(1, repositoryService.createProcessDefinitionQuery().list().size());
		ProcessDefinition pd = repositoryService.createProcessDefinitionQuery().singleResult();
		Assert.assertNotNull(pd);
		String processDefId = pd.getId();

		// 启动流程实例
		ProcessInstance instance = _processEngine.getRuntimeService().startProcessInstanceByKey(pd.getKey());
		Assert.assertNotNull(instance);

		TaskService taskService = _processEngine.getTaskService();

		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateGroup("management").count());
		Assert.assertEquals(0, taskService.createTaskQuery().taskCandidateGroup("engineering").count());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateUser("kermit").count());
		Assert.assertEquals(0, taskService.createTaskQuery().taskAssignee("kermit").count());
		Assert.assertEquals(0, taskService.createTaskQuery().taskCandidateUser("bluejoe").count());

		//允许step2可以让engineering操作
		myActivityPermissionManager
				.addEntry(processDefId, "step2", null, new String[] { "engineering" }, new String[0]);

		//对现有的task没有影响
		Assert.assertEquals(0, taskService.createTaskQuery().taskCandidateGroup("engineering").count());
		_processEngine.getRuntimeService().deleteProcessInstance(instance.getId(), "test");

		//再启动一个流程
		instance = _processEngine.getRuntimeService().startProcessInstanceByKey(pd.getKey());
		//engineering应该可以执行任务了
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateGroup("engineering").count());
		Assert.assertEquals(0, taskService.createTaskQuery().taskCandidateGroup("management").count());
		_processEngine.getRuntimeService().deleteProcessInstance(instance.getId(), "test");

		//允许step2可以让engineering操作
		myActivityPermissionManager.addEntry(processDefId, "step2", null, new String[] { "engineering", "management" },
			new String[] { "neo" });

		//再启动一个流程
		instance = _processEngine.getRuntimeService().startProcessInstanceByKey(pd.getKey());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateGroup("engineering").count());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateGroup("management").count());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateUser("bluejoe").count());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateUser("neo").count());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateUser("kermit").count());
		_processEngine.getRuntimeService().deleteProcessInstance(instance.getId(), "test");

		//代理关系
		InMemoryDelegationDetailsStore imdm = _ctx.getBean(InMemoryDelegationDetailsStore.class);
		imdm.add("neo", "alex");
		//再启动一个流程
		instance = _processEngine.getRuntimeService().startProcessInstanceByKey(pd.getKey());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateUser("neo").count());
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateUser("alex").count());
		_processEngine.getRuntimeService().deleteProcessInstance(instance.getId(), "test");

		//设置屏蔽被代理人
		((TaskDelagation) (((ReplaceTaskAssignmentManager) (((ProcessEngineConfigurationEx) _processEngine
				.getProcessEngineConfiguration()).getStartEngineEventListeners().get(2))).getAssignmentHandlers()
				.get(1))).setHideDelegated(true);

		//再启动一个流程
		instance = _processEngine.getRuntimeService().startProcessInstanceByKey(pd.getKey());
		//neo被屏蔽了
		Assert.assertEquals(1, taskService.createTaskQuery().taskCandidateUser("alex").count());
		Assert.assertEquals(0, taskService.createTaskQuery().taskCandidateUser("neo").count());
		TaskEntity taskEntity = (TaskEntity) taskService.createTaskQuery().active().taskCandidateUser("alex")
				.singleResult();
		List<IdentityLink> list = taskService.getIdentityLinksForTask(taskEntity.getId());
		//taskEntity.getIdentityLinks();
		Thread.sleep(600000);
	}
}
