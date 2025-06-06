/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) OWASP Foundation. All Rights Reserved.
 */
package org.dependencytrack.notification;

import alpine.notification.Notification;
import alpine.notification.NotificationLevel;
import io.pebbletemplates.pebble.PebbleEngine;
import org.dependencytrack.PersistenceCapableTest;
import org.dependencytrack.model.Bom;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.NotificationPublisher;
import org.dependencytrack.model.NotificationRule;
import org.dependencytrack.model.Project;
import org.dependencytrack.model.Tag;
import org.dependencytrack.model.Vex;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.notification.publisher.DefaultNotificationPublishers;
import org.dependencytrack.notification.publisher.PublishContext;
import org.dependencytrack.notification.publisher.Publisher;
import org.dependencytrack.notification.vo.AnalysisDecisionChange;
import org.dependencytrack.notification.vo.BomConsumedOrProcessed;
import org.dependencytrack.notification.vo.BomProcessingFailed;
import org.dependencytrack.notification.vo.BomValidationFailed;
import org.dependencytrack.notification.vo.NewVulnerabilityIdentified;
import org.dependencytrack.notification.vo.NewVulnerableDependency;
import org.dependencytrack.notification.vo.PolicyViolationIdentified;
import org.dependencytrack.notification.vo.VexConsumedOrProcessed;
import org.dependencytrack.notification.vo.ViolationAnalysisDecisionChange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRouterTest extends PersistenceCapableTest {

    @Test
    void testNullNotification() {
        Notification notification = null;
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testInvalidNotification() {
        Notification notification = new Notification();
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testNoRules() {
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testValidMatchingRule() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should not be limited to any projects - so set projects to null
        Project affectedProject = qm.createProject("Test Project", null, "1.0", null, null, null, true, false);
        Component affectedComponent = new Component();
        affectedComponent.setProject(affectedProject);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), null, null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(1, rules.size());
    }

    @Test
    void testValidMatchingProjectLimitingRule() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a project which will later be matched on
        List<Project> projects = new ArrayList<>();
        Project project = qm.createProject("Test Project", null, "1.0", null, null, null, true, false);
        projects.add(project);
        rule.setProjects(projects);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should be limited to only specific projects - Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        affectedProjects.add(project);
        Component affectedComponent = new Component();
        affectedComponent.setProject(project);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(1, rules.size());
    }

    @Test
    void testValidNonMatchingProjectLimitingRule() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a project which will later be matched on
        List<Project> projects = new ArrayList<>();
        Project project = qm.createProject("Test Project", null, "1.0", null, null, null, true, false);
        projects.add(project);
        rule.setProjects(projects);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should be limited to only specific projects - Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        Project affectedProject = qm.createProject("Affected Project", null, "1.0", null, null, null, true, false);
        affectedProjects.add(affectedProject);
        Component affectedComponent = new Component();
        affectedComponent.setProject(affectedProject);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testValidMatchingRuleAndPublisherInform()  {
        NotificationPublisher publisher = createMockPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        rule.setPublisherConfig("{\"destination\":\"testDestination\"}");
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should not be limited to any projects - so set projects to null
        Project affectedProject = qm.createProject("Test Project", null, "1.0", null, null, null, true, false);
        Component affectedComponent = new Component();
        affectedComponent.setProject(affectedProject);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), null, null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        router.inform(notification);
        JsonObject providedConfig = MockPublisher.getConfig();
        Assertions.assertEquals(MockPublisher.MOCK_PUBLISHER_TEMPLATE_CONTENT, providedConfig.getString(Publisher.CONFIG_TEMPLATE_KEY));
        Assertions.assertEquals(MockPublisher.MOCK_PUBLISHER_TEMPLATE_MIME_TYPE, providedConfig.getString(Publisher.CONFIG_TEMPLATE_MIME_TYPE_KEY));
        Assertions.assertEquals("testDestination", providedConfig.getString(Publisher.CONFIG_DESTINATION));
    }

    @Test
    void testValidMatchingProjectLimitingRuleAndPublisherInform()  {
        NotificationPublisher publisher = createMockPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        rule.setPublisherConfig("{\"destination\":\"testDestination\"}");
        // Creates a project which will later be matched on
        List<Project> projects = new ArrayList<>();
        Project firstProject = qm.createProject("Test Project 1", null, "1.0", null, null, null, true, false);
        projects.add(firstProject);
        rule.setProjects(projects);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should be limited to only specific projects - Set the projects which are affected by the notification event
        Project secondProject = qm.createProject("Test Project 2", null, "1.0", null, null, null, true, false);
        Set<Project> affectedProjects = new HashSet<>();
        affectedProjects.add(firstProject);
        affectedProjects.add(secondProject);
        Component affectedComponent = new Component();
        affectedComponent.setProject(firstProject);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        router.inform(notification);
        JsonObject providedConfig = MockPublisher.getConfig();
        Assertions.assertEquals(MockPublisher.MOCK_PUBLISHER_TEMPLATE_CONTENT, providedConfig.getString(Publisher.CONFIG_TEMPLATE_KEY));
        Assertions.assertEquals(MockPublisher.MOCK_PUBLISHER_TEMPLATE_MIME_TYPE, providedConfig.getString(Publisher.CONFIG_TEMPLATE_MIME_TYPE_KEY));
        Assertions.assertEquals("testDestination", providedConfig.getString(Publisher.CONFIG_DESTINATION));
        Notification providedNotification = MockPublisher.getNotification();
        NewVulnerabilityIdentified providedSubject = (NewVulnerabilityIdentified) providedNotification.getSubject();
        Assertions.assertEquals(1, providedSubject.getAffectedProjects().size());
        Assertions.assertEquals(firstProject.getName(), providedSubject.getAffectedProjects().toArray(new Project[1])[0].getName());
    }

    @Test
    void testValidNonMatchingRule() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.PROJECT_AUDIT_CHANGE);
        rule.setNotifyOn(notifyOn);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should not be limited to any projects - so set projects to null
        Project affectedProject = qm.createProject("Test Project 1", null, "1.0", null, null, null, true, false);
        Component affectedComponent = new Component();
        affectedComponent.setProject(affectedProject);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), null, null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testRuleLevelEqual() {
        final NotificationPublisher publisher = createSlackPublisher();
        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.WARNING, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.NEW_VULNERABILITY));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.WARNING); // Rule level is equal

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).hasSize(1);
    }

    @Test
    void testRuleLevelBelow() {
        final NotificationPublisher publisher = createSlackPublisher();
        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.WARNING, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.NEW_VULNERABILITY));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.ERROR); // Rule level is lower

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).hasSize(1);
    }

    @Test
    void testRuleLevelAbove() {
        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.WARNING, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.NEW_VULNERABILITY));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL); // Rule level is higher

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();
    }

    @Test
    void testDisabledRule() {
        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.NEW_VULNERABILITY));
        rule.setEnabled(false);

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();
    }

    @Test
    void testNewVulnerabilityIdentifiedLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        var componentA = new Component();
        componentA.setProject(projectA);
        componentA.setName("Component A");
        componentA = qm.createComponent(componentA, false);

        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);
        var componentB = new Component();
        componentB.setProject(projectB);
        componentB.setName("Component B");
        componentB = qm.createComponent(componentB, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.NEW_VULNERABILITY));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new NewVulnerabilityIdentified(null, qm.detach(componentB), Set.of(), null));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new NewVulnerabilityIdentified(null, qm.detach(componentA), Set.of(), null));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testNewVulnerableDependencyLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        var componentA = new Component();
        componentA.setProject(projectA);
        componentA.setName("Component A");
        componentA = qm.createComponent(componentA, false);

        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);
        var componentB = new Component();
        componentB.setProject(projectB);
        componentB.setName("Component B");
        componentB = qm.createComponent(componentB, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.NEW_VULNERABLE_DEPENDENCY));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABLE_DEPENDENCY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new NewVulnerableDependency(qm.detach(componentB), null));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new NewVulnerableDependency(qm.detach(componentA), null));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testBomConsumedOrProcessedLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.BOM_CONSUMED));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.BOM_CONSUMED.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new BomConsumedOrProcessed(qm.detach(projectB), "", Bom.Format.CYCLONEDX, ""));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new BomConsumedOrProcessed(qm.detach(projectA), "", Bom.Format.CYCLONEDX, ""));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testBomProcessingFailedLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.BOM_PROCESSING_FAILED));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.BOM_PROCESSING_FAILED.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new BomProcessingFailed(qm.detach(projectB), "", null, Bom.Format.CYCLONEDX, ""));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new BomProcessingFailed(qm.detach(projectA), "", null, Bom.Format.CYCLONEDX, ""));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testBomValidationFailedLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.BOM_VALIDATION_FAILED));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.BOM_VALIDATION_FAILED.name());
        notification.setLevel(NotificationLevel.ERROR);
        notification.setSubject(new BomValidationFailed(qm.detach(projectB), "", null, Bom.Format.CYCLONEDX));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new BomValidationFailed(qm.detach(projectA), "", null, Bom.Format.CYCLONEDX));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testVexConsumedOrProcessedLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.VEX_CONSUMED));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.VEX_CONSUMED.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new VexConsumedOrProcessed(qm.detach(projectB), "", Vex.Format.CYCLONEDX, ""));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new VexConsumedOrProcessed(qm.detach(projectA), "", Vex.Format.CYCLONEDX, ""));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testPolicyViolationIdentifiedLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        var componentA = new Component();
        componentA.setProject(projectA);
        componentA.setName("Component A");
        componentA = qm.createComponent(componentA, false);

        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);
        var componentB = new Component();
        componentB.setProject(projectB);
        componentB.setName("Component B");
        componentB = qm.createComponent(componentB, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.POLICY_VIOLATION));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.POLICY_VIOLATION.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new PolicyViolationIdentified(null, componentB, projectB));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new PolicyViolationIdentified(null, qm.detach(componentA), projectA));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testAnalysisDecisionChangeLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.PROJECT_AUDIT_CHANGE));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.PROJECT_AUDIT_CHANGE.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new AnalysisDecisionChange(null, null, qm.detach(projectB), null));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new AnalysisDecisionChange(null, null, projectA, null));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testViolationAnalysisDecisionChangeLimitedToProject() {
        final Project projectA = qm.createProject("Project A", null, "1.0", null, null, null, true, false);
        var componentA = new Component();
        componentA.setProject(projectA);
        componentA.setName("Component A");
        componentA = qm.createComponent(componentA, false);

        final Project projectB = qm.createProject("Project B", null, "1.0", null, null, null, true, false);
        var componentB = new Component();
        componentB.setProject(projectB);
        componentB.setName("Component B");
        componentB = qm.createComponent(componentB, false);

        final NotificationPublisher publisher = createSlackPublisher();

        final NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyOn(Set.of(NotificationGroup.PROJECT_AUDIT_CHANGE));
        rule.setProjects(List.of(projectA));

        final var notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.PROJECT_AUDIT_CHANGE.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        notification.setSubject(new ViolationAnalysisDecisionChange(null, qm.detach(componentB), null));

        final var router = new NotificationRouter();
        assertThat(router.resolveRules(PublishContext.from(notification), notification)).isEmpty();

        notification.setSubject(new ViolationAnalysisDecisionChange(null, componentA, null));
        assertThat(router.resolveRules(PublishContext.from(notification), notification))
                .satisfiesExactly(resolvedRule -> assertThat(resolvedRule.getName()).isEqualTo("Test Rule"));
    }

    @Test
    void testAffectedChild() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Matching Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a project which will later be matched on
        List<Project> projects = new ArrayList<>();
        Project grandParent = qm.createProject("Test Project Grandparent", null, "1.0", null, null, null, true, false);
        Project parent = qm.createProject("Test Project Parent", null, "1.0", null, grandParent, null, true, false);
        Project child = qm.createProject("Test Project Child", null, "1.0", null, parent, null, true, false);
        Project grandChild = qm.createProject("Test Project Grandchild", null, "1.0", null, child, null, true, false);
        projects.add(grandParent);
        rule.setProjects(projects);
        // Creates a new component
        Component component = new Component();
        component.setProject(grandChild);
        component.setName("acme-lib");
        qm.persist(component);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should be limited to only specific projects - Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        affectedProjects.add(grandChild);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(component), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertTrue(rule.isNotifyChildren());
        Assertions.assertEquals(1, rules.size());
    }

    @Test
    void testAffectedChildNotifyChildrenDisabled() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Matching Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        rule.setNotifyChildren(false);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a project which will later be matched on
        List<Project> projects = new ArrayList<>();
        Project grandParent = qm.createProject("Test Project Grandparent", null, "1.0", null, null, null, true, false);
        Project parent = qm.createProject("Test Project Parent", null, "1.0", null, grandParent, null, true, false);
        Project child = qm.createProject("Test Project Child", null, "1.0", null, parent, null, true, false);
        Project grandChild = qm.createProject("Test Project Grandchild", null, "1.0", null, child, null, true, false);
        projects.add(grandParent);
        rule.setProjects(projects);
        // Creates a new component
        Component component = new Component();
        component.setProject(grandChild);
        component.setName("acme-lib");
        qm.persist(component);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should be limited to only specific projects - Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        affectedProjects.add(grandChild);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(component), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertFalse(rule.isNotifyChildren());
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testAffectedInactiveChild() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a project which will later be matched on
        List<Project> projects = new ArrayList<>();
        Project grandParent = qm.createProject("Test Project Grandparent", null, "1.0", null, null, null, true, false);
        Project parent = qm.createProject("Test Project Parent", null, "1.0", null, grandParent, null, true, false);
        Project child = qm.createProject("Test Project Child", null, "1.0", null, parent, null, true, false);
        Project grandChild = qm.createProject("Test Project Grandchild", null, "1.0", null, child, null, false, false);
        projects.add(grandParent);
        rule.setProjects(projects);
        // Creates a new component
        Component component = new Component();
        component.setProject(grandChild);
        component.setName("acme-lib");
        qm.persist(component);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should be limited to only specific projects - Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        affectedProjects.add(grandChild);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(component), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertTrue(rule.isNotifyChildren());
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testValidMatchingTagLimitingRule() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a tag which will later be matched on
        Tag tag = qm.createTag("test");
        List<Tag> tags = List.of(tag);
        Project project = qm.createProject("Test Project", null, "1.0", tags, null, null, true, false);
        qm.bind(rule, tags);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Notification should be limited to only specific projects - Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        affectedProjects.add(project);
        Component affectedComponent = new Component();
        affectedComponent.setProject(project);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        Assertions.assertEquals(1, rules.size());
    }

    @Test
    void testValidNonMatchingTagLimitingRule() {
        NotificationPublisher publisher = createSlackPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a tag to limit the notifications
        Tag tag = qm.createTag("test");
        List<Tag> tags = List.of(tag);
        qm.createProject("Test Project", null, "1.0", tags, null, null, true, false);
        // Rule should apply only for specific tag
        qm.bind(rule, tags);
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        Project affectedProject = qm.createProject("Affected Project", null, "1.0", null, null, null, true, false);
        affectedProjects.add(affectedProject);
        Component affectedComponent = new Component();
        affectedComponent.setProject(affectedProject);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        List<NotificationRule> rules = router.resolveRules(PublishContext.from(notification), notification);
        // Affected project is not tagged, rule should be empty
        Assertions.assertEquals(0, rules.size());
    }

    @Test
    void testValidMatchingProjectAndTagLimitingRule() {
        NotificationPublisher publisher = createMockPublisher();
        // Creates a new rule and defines when the rule should be triggered (notifyOn)
        NotificationRule rule = qm.createNotificationRule("Test Rule", NotificationScope.PORTFOLIO, NotificationLevel.INFORMATIONAL, publisher);
        Set<NotificationGroup> notifyOn = new HashSet<>();
        notifyOn.add(NotificationGroup.NEW_VULNERABILITY);
        rule.setNotifyOn(notifyOn);
        // Creates a tag to limit the notifications
        Tag tag = qm.createTag("test");
        List<Tag> tags = List.of(tag);
        Project taggedProject = qm.createProject("Test Project", null, "1.0", tags, null, null, true, false);
        // Rule should apply for specific tag
        qm.bind(rule, tags);
        // Rule should also apply for specific project
        Project otherAffectedProject = qm.createProject("Affected Project", null, "1.0", null, null, null, true, false);
        rule.setProjects(List.of(otherAffectedProject));
        // Creates a new notification
        Notification notification = new Notification();
        notification.setScope(NotificationScope.PORTFOLIO.name());
        notification.setGroup(NotificationGroup.NEW_VULNERABILITY.name());
        notification.setLevel(NotificationLevel.INFORMATIONAL);
        // Set the projects which are affected by the notification event
        Set<Project> affectedProjects = new HashSet<>();
        affectedProjects.add(taggedProject);
        affectedProjects.add(otherAffectedProject);
        Component affectedComponent = new Component();
        affectedComponent.setProject(taggedProject);
        affectedComponent.setName("acme-lib");
        qm.persist(affectedComponent);
        NewVulnerabilityIdentified subject = new NewVulnerabilityIdentified(
                new Vulnerability(), qm.detach(affectedComponent), qm.detach(affectedProjects), null);
        notification.setSubject(subject);
        // Ok, let's test this
        NotificationRouter router = new NotificationRouter();
        router.inform(notification);
        // Affected project is not tagged, rules should be empty
        Notification providedNotification = MockPublisher.getNotification();
        NewVulnerabilityIdentified providedSubject = (NewVulnerabilityIdentified) providedNotification.getSubject();
        Assertions.assertEquals(2, providedSubject.getAffectedProjects().size());
    }

    private NotificationPublisher createSlackPublisher() {
        return qm.createNotificationPublisher(
                DefaultNotificationPublishers.SLACK.getPublisherName(),
                DefaultNotificationPublishers.SLACK.getPublisherDescription(),
                DefaultNotificationPublishers.SLACK.getPublisherClass(),
                null, DefaultNotificationPublishers.SLACK.getTemplateMimeType(),
                DefaultNotificationPublishers.SLACK.isDefaultPublisher()
        );
    }

    private NotificationPublisher createMockPublisher() {
        return qm.createNotificationPublisher(
                MockPublisher.MOCK_PUBLISHER_NAME,
                MockPublisher.MOCK_PUBLISHER_DESCRIPTION,
                NotificationRouterTest.MockPublisher.class,
                MockPublisher.MOCK_PUBLISHER_TEMPLATE_CONTENT, MockPublisher.MOCK_PUBLISHER_TEMPLATE_MIME_TYPE,
                true
        );
    }

    public static class MockPublisher implements Publisher {

        public static final String MOCK_PUBLISHER_NAME = "mockPublisher";

        public static final String MOCK_PUBLISHER_DESCRIPTION = "Mock publisher";

        public static final String MOCK_PUBLISHER_TEMPLATE_CONTENT = "templateContent";

        public static final String MOCK_PUBLISHER_TEMPLATE_MIME_TYPE = "application/json";

        public static JsonObject config;

        public static Notification notification;

        public MockPublisher() {
            config = null;
        }

        @Override
        public void inform(final PublishContext ctx, Notification notification, JsonObject config) {
            MockPublisher.config = config;
            MockPublisher.notification = notification;
        }

        public static JsonObject getConfig() {
            return config;
        }

        public static Notification getNotification() {
            return notification;
        }

        @Override
        public PebbleEngine getTemplateEngine() {
            return new PebbleEngine.Builder().newLineTrimming(false).build();
        }
    }
}
