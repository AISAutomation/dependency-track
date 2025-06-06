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
package org.dependencytrack.persistence;

import alpine.common.logging.Logger;
import alpine.event.framework.Event;
import alpine.model.ApiKey;
import alpine.model.Permission;
import alpine.model.Team;
import alpine.model.UserPrincipal;
import alpine.notification.Notification;
import alpine.notification.NotificationLevel;
import alpine.persistence.PaginatedResult;
import alpine.persistence.ScopedCustomization;
import alpine.resources.AlpineRequest;
import alpine.server.util.DbUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.packageurl.PackageURL;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.datanucleus.api.jdo.JDOQuery;
import org.dependencytrack.auth.Permissions;
import org.dependencytrack.event.IndexEvent;
import org.dependencytrack.event.ProjectMetricsUpdateEvent;
import org.dependencytrack.exception.ProjectOperationException;
import org.dependencytrack.model.Analysis;
import org.dependencytrack.model.AnalysisComment;
import org.dependencytrack.model.Classifier;
import org.dependencytrack.model.Component;
import org.dependencytrack.model.ComponentProperty;
import org.dependencytrack.model.ConfigPropertyConstants;
import org.dependencytrack.model.FindingAttribution;
import org.dependencytrack.model.PolicyViolation;
import org.dependencytrack.model.Project;
import org.dependencytrack.model.ProjectCollectionLogic;
import org.dependencytrack.model.ProjectMetadata;
import org.dependencytrack.model.ProjectProperty;
import org.dependencytrack.model.ProjectVersion;
import org.dependencytrack.model.ServiceComponent;
import org.dependencytrack.model.Tag;
import org.dependencytrack.model.Vulnerability;
import org.dependencytrack.notification.NotificationConstants;
import org.dependencytrack.notification.NotificationGroup;
import org.dependencytrack.notification.NotificationScope;
import org.dependencytrack.util.NotificationUtil;

import javax.jdo.FetchPlan;
import javax.jdo.PersistenceManager;
import javax.jdo.Query;
import javax.jdo.metadata.MemberMetadata;
import javax.jdo.metadata.TypeMetadata;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.datanucleus.PropertyNames.PROPERTY_QUERY_SQL_ALLOWALL;
import static org.dependencytrack.util.PersistenceUtil.assertPersistent;
import static org.dependencytrack.util.PersistenceUtil.assertPersistentAll;

final class ProjectQueryManager extends QueryManager implements IQueryManager {

    private static final Logger LOGGER = Logger.getLogger(ProjectQueryManager.class);

    /**
     * Constructs a new QueryManager.
     * @param pm a PersistenceManager object
     */
    ProjectQueryManager(final PersistenceManager pm) {
        super(pm);
    }

    /**
     * Constructs a new QueryManager.
     * @param pm a PersistenceManager object
     * @param request an AlpineRequest object
     */
    ProjectQueryManager(final PersistenceManager pm, final AlpineRequest request) {
        super(pm, request);
    }

    /**
     * Returns a list of all projects.
     * @return a List of Projects
     */
    @Override
    public PaginatedResult getProjects(final boolean includeMetrics, final boolean excludeInactive, final boolean onlyRoot, final Team notAssignedToTeam) {
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc, version desc");
        }

        var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive);

        if (onlyRoot){
            filterBuilder.excludeChildProjects();
            query.getFetchPlan().addGroup(Project.FetchGroup.ALL.name());
        }

        if(notAssignedToTeam != null) {
            filterBuilder.notWithTeam(notAssignedToTeam);
        }

        if (filter != null) {
            final String filterString = ".*" + filter.toLowerCase() + ".*";
            final Tag tag = getTagByName(filter.trim());

            if (tag != null) {
                filterBuilder = filterBuilder.withFuzzyNameOrExactTag(filterString, tag);

            } else {
                filterBuilder = filterBuilder.withFuzzyName(filterString);
            }
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        result = execute(query, params);
        if (includeMetrics) {
            // Populate each Project object in the paginated result with transitive related
            // data to minimize the number of round trips a client needs to make, process, and render.
            for (Project project : result.getList(Project.class)) {
                project.setMetrics(getMostRecentProjectMetrics(project));
            }
        }
        return result;
    }

    /**
     * Returns a list of all projects.
     * @return a List of Projects
     */
    @Override
    public PaginatedResult getProjects(final boolean includeMetrics) {
        return getProjects(includeMetrics, false, false, null);
    }

    /**
     * Returns a list of all projects.
     * @return a List of Projects
     */
    @Override
    public PaginatedResult getProjects() {
        return getProjects(false);
    }

    /**
     * Returns a list of projects by their name.
     * @param name the name of the Projects (required)
     * @return a List of Project objects
     */
    @Override
    public PaginatedResult getProjects(final String name, final boolean excludeInactive, final boolean onlyRoot, final Team notAssignedToTeam) {
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("version desc");
        }

        final var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withName(name);

        if (onlyRoot) {
            filterBuilder.excludeChildProjects();
            query.getFetchPlan().addGroup(Project.FetchGroup.ALL.name());
        }

        if(notAssignedToTeam != null) {
            filterBuilder.notWithTeam(notAssignedToTeam);
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        return execute(query, params);
    }

    /**
     * Returns a project by its uuid.
     * @param uuid the uuid of the Project (required)
     * @return a Project object, or null if not found
     */
    @Override
    public Project getProject(final String uuid) {
        final Project project = getObjectByUuid(Project.class, uuid, Project.FetchGroup.ALL.name());
        if (project != null) {
            // set Metrics to minimize the number of round trips a client needs to make
            project.setMetrics(getMostRecentProjectMetrics(project));
            // set ProjectVersions to minimize the number of round trips a client needs to make
            project.setVersions(getProjectVersions(project));
        }
        return project;
    }


    /**
     * Returns a project by its name and version.
     * @param name the name of the Project (required)
     * @param version the version of the Project (or null)
     * @return a Project object, or null if not found
     */
    @Override
    public Project getProject(final String name, final String version) {
        final Query<Project> query = pm.newQuery(Project.class);

        final var filterBuilder = new ProjectQueryFilterBuilder()
                .withName(name)
                .withVersion(version);

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        query.setFilter(queryFilter);
        query.setRange(0, 1);
        final Project project = singleResult(query.executeWithMap(params));
        if (project != null) {
            // set Metrics to prevent extra round trip
            project.setMetrics(getMostRecentProjectMetrics(project));
            // set ProjectVersions to prevent extra round trip
            project.setVersions(getProjectVersions(project));
        }
        return project;
    }


    /**
     * Returns the latest version of a project by its name.
     *
     * @param name the name of the Project (required)
     * @return a Project object representing the latest version, or null if not found
     */
    @Override
    public Project getLatestProjectVersion(final String name) {
        final Query<Project> query = pm.newQuery(Project.class);

        final var filterBuilder = new ProjectQueryFilterBuilder()
                .withName(name)
                .onlyLatestVersion();

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        query.setFilter(queryFilter);
        query.setRange(0, 1);

        final Project project = singleResult(query.executeWithMap(params));
        if (project != null) {
            // set Metrics to prevent extra round trip
            project.setMetrics(getMostRecentProjectMetrics(project));
            // set ProjectVersions to prevent extra round trip
            project.setVersions(getProjectVersions(project));
        }
        return project;
    }

    /**
     * Returns a list of projects that are accessible by the specified team.
     * @param team the team the has access to Projects
     * @return a List of Project objects
     */
    @Override
    public PaginatedResult getProjects(final Team team, final boolean excludeInactive, final boolean bypass, final boolean onlyRoot) {
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc, version desc, id asc");
        }

        final var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withTeam(team);

        if (onlyRoot){
            filterBuilder.excludeChildProjects();
            query.getFetchPlan().addGroup(Project.FetchGroup.ALL.name());
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, bypass);
        return execute(query, params);
    }

    /**
     * Returns a paginated result of projects by tag.
     * @param tag the tag associated with the Project
     * @return a List of Projects that contain the tag
     */
    @Override
    public PaginatedResult getProjects(final Tag tag, final boolean includeMetrics, final boolean excludeInactive, final boolean onlyRoot) {
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc");
        }

        var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withTag(tag);

        if (onlyRoot){
            filterBuilder.excludeChildProjects();
            query.getFetchPlan().addGroup(Project.FetchGroup.ALL.name());
        }

        if (filter != null) {
            final String filterString = ".*" + filter.toLowerCase() + ".*";
            filterBuilder = filterBuilder.withFuzzyName(filterString);
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        result = execute(query, params);
        if (includeMetrics) {
            // Populate each Project object in the paginated result with transitive related
            // data to minimize the number of round trips a client needs to make, process, and render.
            for (Project project : result.getList(Project.class)) {
                project.setMetrics(getMostRecentProjectMetrics(project));
            }
        }
        return result;
    }

    /**
     * Returns a paginated result of projects by classifier.
     * @param classifier the classifier of the Project
     * @return a List of Projects of the specified classifier
     */
    @Override
    public PaginatedResult getProjects(final Classifier classifier, final boolean includeMetrics, final boolean excludeInactive, final boolean onlyRoot) {
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc");
        }

        final var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withClassifier(classifier);

        if (onlyRoot){
            filterBuilder.excludeChildProjects();
            query.getFetchPlan().addGroup(Project.FetchGroup.ALL.name());
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        result = execute(query, params);
        if (includeMetrics) {
            // Populate each Project object in the paginated result with transitive related
            // data to minimize the number of round trips a client needs to make, process, and render.
            for (Project project : result.getList(Project.class)) {
                project.setMetrics(getMostRecentProjectMetrics(project));
            }
        }
        return result;
    }

    /**
     * Returns a paginated result of projects by tag.
     * @param tag the tag associated with the Project
     * @return a List of Projects that contain the tag
     */
    @Override
    public PaginatedResult getProjects(final Tag tag) {
        return getProjects(tag, false, false, false);
    }

    /**
     * Creates a new Project.
     * @param name the name of the project to create
     * @param description a description of the project
     * @param version the project version
     * @param tags a List of Tags - these will be resolved if necessary
     * @param parent an optional parent Project
     * @param purl an optional Package URL
     * @param active specified if the project is active
     * @param commitIndex specifies if the search index should be committed (an expensive operation)
     * @return the created Project
     */
    @Override
    public Project createProject(String name, String description, String version, List<Tag> tags, Project parent,
                                 PackageURL purl, boolean active, boolean commitIndex) {
        return createProject(name, description, version, tags, parent, purl, active, false, commitIndex);
    }

    /**
     * Creates a new Project.
     * @param name the name of the project to create
     * @param description a description of the project
     * @param version the project version
     * @param tags a List of Tags - these will be resolved if necessary
     * @param parent an optional parent Project
     * @param purl an optional Package URL
     * @param active specified if the project is active
     * @param commitIndex specifies if the search index should be committed (an expensive operation)
     * @return the created Project
     */
    @Override
    public Project createProject(String name, String description, String version, List<Tag> tags, Project parent,
                                 PackageURL purl, boolean active, boolean isLatest, boolean commitIndex) {
        final Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setVersion(version);
        project.setParent(parent);
        project.setPurl(purl);
        project.setActive(active);
        project.setIsLatest(isLatest);
        return createProject(project, tags, commitIndex);
    }

    /**
     * Creates a new Project.
     * @param project the project to create
     * @param tags a List of Tags - these will be resolved if necessary
     * @param commitIndex specifies if the search index should be committed (an expensive operation)
     * @return the created Project
     */
    @Override
    public Project createProject(final Project project, Collection<Tag> tags, boolean commitIndex) {
        if (project.getParent() != null && !Boolean.TRUE.equals(project.getParent().isActive())){
            throw new IllegalArgumentException("An inactive Parent cannot be selected as parent");
        }
        final Project oldLatestProject = project.isLatest() ? getLatestProjectVersion(project.getName()) : null;
        final Project result = callInTransaction(() -> {
            // Remove isLatest flag from current latest project version, if the new project will be the latest
            if(oldLatestProject != null) {
                oldLatestProject.setIsLatest(false);
                persist(oldLatestProject);
            }

            if (project.getCollectionLogic() == ProjectCollectionLogic.AGGREGATE_DIRECT_CHILDREN_WITH_TAG
                && project.getCollectionTag() != null) {
                final Tag resolvedCollectionTag = resolveTags(List.of(project.getCollectionTag())).iterator().next();
                project.setCollectionTag(resolvedCollectionTag);
            } else {
                project.setCollectionTag(null);
            }

            // Ensure that tags are not created implicitly but go through resolveTags instead.
            final Set<Tag> resolvedTags = resolveTags(tags);
            project.setTags(null);

            final Project newProject = persist(project);
            bind(project, resolvedTags);
            return newProject;
        });

        if(oldLatestProject != null) {
            // if we removed isLatest flag from old version, dispatch update event for the old version
            Event.dispatch(new IndexEvent(IndexEvent.Action.UPDATE, oldLatestProject));
        }
        Event.dispatch(new IndexEvent(IndexEvent.Action.CREATE, result));
        Notification.dispatch(new Notification()
                .scope(NotificationScope.PORTFOLIO)
                .group(NotificationGroup.PROJECT_CREATED)
                .title(NotificationConstants.Title.PROJECT_CREATED)
                .level(NotificationLevel.INFORMATIONAL)
                .content(result.getName() + " was created")
                .subject(NotificationUtil.toJson(pm.detachCopy(result)))
        );
        commitSearchIndex(commitIndex, Project.class);
        return result;
    }

    /**
     * Updates an existing Project.
     * @param transientProject the project to update
     * @param commitIndex specifies if the search index should be committed (an expensive operation)
     * @return the updated Project
     */
    @Override
    public Project updateProject(Project transientProject, boolean commitIndex) {
        final Project project = getObjectByUuid(Project.class, transientProject.getUuid());

        Project oldParent = project.getParent();
        boolean scheduleProjectMetricsUpdate = this.needScheduleProjectMetricsUpdate(project, transientProject);
        boolean scheduleParentMetricsUpdate = this.needScheduleParentMetricsUpdate(transientProject, scheduleProjectMetricsUpdate);
        boolean scheduleOldParentMetricsUpdate = this.needScheduleOldParentMetricsUpdate(oldParent, transientProject);

        project.setAuthors(transientProject.getAuthors());
        project.setPublisher(transientProject.getPublisher());
        project.setManufacturer(transientProject.getManufacturer());
        project.setSupplier(transientProject.getSupplier());
        project.setGroup(transientProject.getGroup());
        project.setName(transientProject.getName());
        project.setDescription(transientProject.getDescription());
        project.setVersion(transientProject.getVersion());
        project.setClassifier(transientProject.getClassifier());
        project.setCpe(transientProject.getCpe());
        project.setPurl(transientProject.getPurl());
        project.setSwidTagId(transientProject.getSwidTagId());
        project.setExternalReferences(transientProject.getExternalReferences());

        // prevent illegal states of collection projects (must not contain components or services)
        if(transientProject.getCollectionLogic() != null
                && !project.getCollectionLogic().equals(transientProject.getCollectionLogic())) {
            if(!transientProject.getCollectionLogic().equals(ProjectCollectionLogic.NONE)
                    && (hasComponents(project) || hasServiceComponents(project))) {
                throw new IllegalArgumentException("Project cannot be made a collection project while it has components or services!");
            }
            project.setCollectionLogic(transientProject.getCollectionLogic());
        }

        if (Boolean.TRUE.equals(project.isActive()) && !Boolean.TRUE.equals(transientProject.isActive()) && hasActiveChild(project)){
            throw new IllegalArgumentException("Project cannot be set to inactive if active children are present.");
        }
        project.setActive(transientProject.isActive());

        final Project oldLatestProject;
        if(Boolean.TRUE.equals(transientProject.isLatest()) && Boolean.FALSE.equals(project.isLatest())) {
            oldLatestProject = getLatestProjectVersion(project.getName());
        } else {
            oldLatestProject = null;
        }
        project.setIsLatest(transientProject.isLatest());

        if (transientProject.getParent() != null && transientProject.getParent().getUuid() != null) {
            if (project.getUuid().equals(transientProject.getParent().getUuid())){
                throw new IllegalArgumentException("A project cannot select itself as a parent");
            }
            Project parent = getObjectByUuid(Project.class, transientProject.getParent().getUuid());
            if (!Boolean.TRUE.equals(parent.isActive())){
                throw new IllegalArgumentException("An inactive project cannot be selected as a parent");
            } else if (isChildOf(parent, transientProject.getUuid())){
                throw new IllegalArgumentException("The new parent project cannot be a child of the current project.");
            } else {
                project.setParent(parent);
            }
            project.setParent(parent);
        }else {
            project.setParent(null);
        }

        final Project result = callInTransaction(() -> {
            // Remove isLatest flag from current latest project version, if this project will be the latest now
            if(oldLatestProject != null) {
                oldLatestProject.setIsLatest(false);
                persist(oldLatestProject);
            }

            final Set<Tag> resolvedTags = resolveTags(transientProject.getTags());
            bind(project, resolvedTags);

            // Set collection tag only if selected collectionLogic requires it. Clear it otherwise.
            if(transientProject.getCollectionLogic().equals(ProjectCollectionLogic.AGGREGATE_DIRECT_CHILDREN_WITH_TAG) &&
                    transientProject.getCollectionTag() != null) {
                final Set<Tag> resolvedCollectionTags = resolveTags(Collections.singletonList(
                        transientProject.getCollectionTag()
                ));
                project.setCollectionTag(resolvedCollectionTags.iterator().next());
            } else {
                project.setCollectionTag(null);
            }

            return persist(project);
        });

        if(oldLatestProject != null) {
            // if we removed isLatest flag from old version, dispatch update event for the old version
            Event.dispatch(new IndexEvent(IndexEvent.Action.UPDATE, oldLatestProject));
        }
        Event.dispatch(new IndexEvent(IndexEvent.Action.UPDATE, result));
        commitSearchIndex(commitIndex, Project.class);

        this.scheduleMetricsUpdates(project, oldParent, scheduleProjectMetricsUpdate, scheduleParentMetricsUpdate, scheduleOldParentMetricsUpdate);

        return result;
    }

    /**
     * if collection logic changes or collection tag changes or version changes we need to re-calculate the project's metrics in the end
     */
    private boolean needScheduleProjectMetricsUpdate(Project project, Project transientProject) {
        return project.getCollectionLogic() != transientProject.getCollectionLogic()
                || (transientProject.getCollectionTag() != null && !transientProject.getCollectionTag().equals(project.getCollectionTag()));
    }

    /**
     * if parent is collection schedule an update, unless this project itself is scheduled already since that will trigger a parent update, too
     */
    private boolean needScheduleParentMetricsUpdate(Project transientProject, boolean scheduleProjectMetricsUpdate) {
        return !scheduleProjectMetricsUpdate
                && transientProject.getParent() != null
                && transientProject.getParent().getCollectionLogic() != ProjectCollectionLogic.NONE;
    }

    /**
     * if project gets a new parent and old parent was collection, we need to update old parent's metrics
     */
    private boolean needScheduleOldParentMetricsUpdate(Project oldParent, Project transientProject) {
        return oldParent != transientProject.getParent()
                && oldParent != null
                && oldParent.getCollectionLogic() != ProjectCollectionLogic.NONE;
    }

    /**
     * Schedules metrics updates for the project itself, the new parent and/or the old parent, as necessary.
     */
    private void scheduleMetricsUpdates(Project project, Project oldParent, boolean scheduleProjectMetricsUpdate, boolean scheduleParentMetricsUpdate, boolean scheduleOldParentMetricsUpdate) {
        if(scheduleProjectMetricsUpdate) {
            Event.dispatch(new ProjectMetricsUpdateEvent(project.getUuid()));
        }
        if(scheduleParentMetricsUpdate) {
            Event.dispatch(new ProjectMetricsUpdateEvent(project.getParent().getUuid()));
        }
        if(scheduleOldParentMetricsUpdate) {
            Event.dispatch(new ProjectMetricsUpdateEvent(oldParent.getUuid()));
        }
    }

    @Override
    public Project clone(
            final UUID from,
            final String newVersion,
            final boolean includeTags,
            final boolean includeProperties,
            final boolean includeComponents,
            final boolean includeServices,
            final boolean includeAuditHistory,
            final boolean includeACL,
            final boolean includePolicyViolations,
            final boolean makeCloneLatest
    ) {
        final AtomicReference<Project> oldLatestProject = new AtomicReference<>();
        final var jsonMapper = new JsonMapper();
        final Project clonedProject = callInTransaction(() -> {
            final Project source = getObjectByUuid(Project.class, from, Project.FetchGroup.ALL.name());
            if (source == null) {
                LOGGER.warn("Project was supposed to be cloned, but it does not exist anymore");
                return null;
            }
            if (doesProjectExist(source.getName(), newVersion)) {
                // Project cloning is an asynchronous process. When receiving the clone request, we already perform
                // this check. It is possible though that a project with the new version is created synchronously
                // between the clone event being dispatched, and it being processed.
                LOGGER.warn("Project was supposed to be cloned to version %s, but that version already exists".formatted(newVersion));
                return null;
            }
            if(makeCloneLatest) {
                oldLatestProject.set(source.isLatest() ? source : getLatestProjectVersion(source.getName()));
            } else {
                oldLatestProject.set(null);
            }
            Project project = new Project();
            project.setAuthors(source.getAuthors());
            project.setManufacturer(source.getManufacturer());
            project.setSupplier(source.getSupplier());
            project.setPublisher(source.getPublisher());
            project.setGroup(source.getGroup());
            project.setName(source.getName());
            project.setDescription(source.getDescription());
            project.setVersion(newVersion);
            project.setClassifier(source.getClassifier());
            project.setCollectionLogic(source.getCollectionLogic());
            project.setCollectionTag(source.getCollectionTag());
            project.setActive(source.isActive());
            project.setIsLatest(makeCloneLatest);
            project.setCpe(source.getCpe());
            project.setPurl(source.getPurl());
            project.setSwidTagId(source.getSwidTagId());
            if (source.getDirectDependencies() != null && includeComponents && includeServices) {
                project.setDirectDependencies(source.getDirectDependencies());
            }
            project.setParent(source.getParent());

            // Remove isLatest flag from current latest project version, if this project will be the latest now
            if(oldLatestProject.get() != null) {
                oldLatestProject.get().setIsLatest(false);
                persist(oldLatestProject.get());
            }

            project = persist(project);

            if (source.getMetadata() != null) {
                final var metadata = new ProjectMetadata();
                metadata.setProject(project);
                metadata.setAuthors(source.getMetadata().getAuthors());
                metadata.setSupplier(source.getMetadata().getSupplier());
                persist(metadata);
            }

            if (includeTags) {
                for (final Tag tag : source.getTags()) {
                    tag.getProjects().add(project);
                    persist(tag);
                }
            }

            if (includeProperties && source.getProperties() != null) {
                for (final ProjectProperty sourceProperty : source.getProperties()) {
                    final ProjectProperty property = new ProjectProperty();
                    property.setProject(project);
                    property.setPropertyType(sourceProperty.getPropertyType());
                    property.setGroupName(sourceProperty.getGroupName());
                    property.setPropertyName(sourceProperty.getPropertyName());
                    property.setPropertyValue(sourceProperty.getPropertyValue());
                    property.setDescription(sourceProperty.getDescription());
                    persist(property);
                }
            }

            final var projectDirectDepsSourceComponentUuids = new HashSet<UUID>();
            if (project.getDirectDependencies() != null) {
                projectDirectDepsSourceComponentUuids.addAll(
                        parseDirectDependenciesUuids(jsonMapper, project.getDirectDependencies()));
            }

            final var clonedComponentById = new HashMap<Long, Component>();
            final var clonedComponentBySourceComponentId = new HashMap<Long, Component>();
            final var directDepsSourceComponentUuidsByClonedComponentId = new HashMap<Long, Set<UUID>>();
            final var clonedComponentUuidBySourceComponentUuid = new HashMap<UUID, UUID>();

            if (includeComponents) {
                final List<Component> sourceComponents = getAllComponents(source);
                if (sourceComponents != null) {
                    for (final Component sourceComponent : sourceComponents) {
                        final Component clonedComponent = cloneComponent(sourceComponent, project, false);

                        if (sourceComponent.getProperties() != null && !sourceComponent.getProperties().isEmpty()) {
                            final var clonedProperties = new ArrayList<ComponentProperty>(sourceComponent.getProperties().size());
                            for (final ComponentProperty sourceProperty : sourceComponent.getProperties()) {
                                final ComponentProperty clonedProperty = new ComponentProperty();
                                clonedProperty.setComponent(clonedComponent);
                                clonedProperty.setPropertyType(sourceProperty.getPropertyType());
                                clonedProperty.setGroupName(sourceProperty.getGroupName());
                                clonedProperty.setPropertyName(sourceProperty.getPropertyName());
                                clonedProperty.setPropertyValue(sourceProperty.getPropertyValue());
                                clonedProperty.setDescription(sourceProperty.getDescription());
                                clonedProperties.add(clonedProperty);
                            }

                            persist(clonedProperties);
                            clonedComponent.setProperties(clonedProperties);
                        }

                        // Add vulnerabilties and finding attribution from the source component to the cloned component
                        for (Vulnerability vuln : sourceComponent.getVulnerabilities()) {
                            final FindingAttribution sourceAttribution = this.getFindingAttribution(vuln, sourceComponent);
                            this.addVulnerability(vuln, clonedComponent, sourceAttribution.getAnalyzerIdentity(), sourceAttribution.getAlternateIdentifier(), sourceAttribution.getReferenceUrl(), sourceAttribution.getAttributedOn());
                        }

                        clonedComponentById.put(clonedComponent.getId(), clonedComponent);
                        clonedComponentBySourceComponentId.put(sourceComponent.getId(), clonedComponent);
                        clonedComponentUuidBySourceComponentUuid.put(sourceComponent.getUuid(), clonedComponent.getUuid());

                        if (clonedComponent.getDirectDependencies() != null) {
                            final Set<UUID> directDepsUuids = parseDirectDependenciesUuids(jsonMapper, clonedComponent.getDirectDependencies());
                            if (!directDepsUuids.isEmpty()) {
                                directDepsSourceComponentUuidsByClonedComponentId.put(clonedComponent.getId(), directDepsUuids);
                            }
                        }
                    }
                }
            }

            if (!projectDirectDepsSourceComponentUuids.isEmpty()) {
                String directDependencies = project.getDirectDependencies();
                for (final UUID sourceComponentUuid : projectDirectDepsSourceComponentUuids) {
                    final UUID clonedComponentUuid = clonedComponentUuidBySourceComponentUuid.get(sourceComponentUuid);
                    if (clonedComponentUuid != null) {
                        directDependencies = directDependencies.replace(
                                sourceComponentUuid.toString(), clonedComponentUuid.toString());
                    } else {
                        // NB: This may happen when the source project itself is a clone,
                        // and it was cloned before DT v4.12.0.
                        // https://github.com/DependencyTrack/dependency-track/pull/4171
                        LOGGER.warn("""
                                The source project's directDependencies refer to a component with UUID \
                                %s, which does not exist in the project. The cloned project's dependency graph \
                                may be broken as a result. A BOM upload will resolve the issue.\
                                """.formatted(sourceComponentUuid));
                    }
                }

                project.setDirectDependencies(directDependencies);
            }

            for (final long componentId : directDepsSourceComponentUuidsByClonedComponentId.keySet()) {
                final Component component = clonedComponentById.get(componentId);
                final Set<UUID> sourceComponentUuids = directDepsSourceComponentUuidsByClonedComponentId.get(componentId);

                String directDependencies = component.getDirectDependencies();
                for (final UUID sourceComponentUuid : sourceComponentUuids) {
                    final UUID clonedComponentUuid = clonedComponentUuidBySourceComponentUuid.get(sourceComponentUuid);
                    if (clonedComponentUuid != null) {
                        directDependencies = directDependencies.replace(
                                sourceComponentUuid.toString(), clonedComponentUuid.toString());
                    } else {
                        LOGGER.warn("""
                                The directDependencies of component %s refer to a component with UUID \
                                %s, which does not exist in the source project. The cloned project's dependency graph \
                                may be broken as a result. A BOM upload will resolve the issue.\
                                """.formatted(component, sourceComponentUuid));
                    }
                }

                component.setDirectDependencies(directDependencies);
            }

            if (includeServices) {
                final List<ServiceComponent> sourceServices = getAllServiceComponents(source);
                if (sourceServices != null) {
                    for (final ServiceComponent sourceService : sourceServices) {
                        cloneServiceComponent(sourceService, project, false);
                    }
                }
            }

            if (includeAuditHistory && includeComponents) {
                final List<Analysis> analyses = super.getAnalyses(source);
                if (analyses != null) {
                    for (final Analysis sourceAnalysis : analyses) {
                        Analysis analysis = new Analysis();
                        analysis.setAnalysisState(sourceAnalysis.getAnalysisState());
                        final Component clonedComponent = clonedComponentBySourceComponentId.get(sourceAnalysis.getComponent().getId());
                        if (clonedComponent == null) {
                            break;
                        }
                        analysis.setComponent(clonedComponent);
                        analysis.setVulnerability(sourceAnalysis.getVulnerability());
                        analysis.setSuppressed(sourceAnalysis.isSuppressed());
                        analysis.setAnalysisResponse(sourceAnalysis.getAnalysisResponse());
                        analysis.setAnalysisJustification(sourceAnalysis.getAnalysisJustification());
                        analysis.setAnalysisState(sourceAnalysis.getAnalysisState());
                        analysis.setAnalysisDetails(sourceAnalysis.getAnalysisDetails());
                        analysis = persist(analysis);
                        if (sourceAnalysis.getAnalysisComments() != null) {
                            for (final AnalysisComment sourceComment : sourceAnalysis.getAnalysisComments()) {
                                final AnalysisComment analysisComment = new AnalysisComment();
                                analysisComment.setAnalysis(analysis);
                                analysisComment.setTimestamp(sourceComment.getTimestamp());
                                analysisComment.setComment(sourceComment.getComment());
                                analysisComment.setCommenter(sourceComment.getCommenter());
                                persist(analysisComment);
                            }
                        }
                    }
                }
            }

            if (includeACL) {
                List<Team> accessTeams = source.getAccessTeams();
                if (!CollectionUtils.isEmpty(accessTeams)) {
                    project.setAccessTeams(new ArrayList<>(accessTeams));
                }
            }


            if (includeComponents && includePolicyViolations) {
                final List<PolicyViolation> sourcePolicyViolations = getAllPolicyViolations(source);
                if (sourcePolicyViolations != null) {
                    for (final PolicyViolation policyViolation : sourcePolicyViolations) {
                        final Component destinationComponent = clonedComponentBySourceComponentId.get(policyViolation.getComponent().getId());
                        final PolicyViolation clonedPolicyViolation = clonePolicyViolation(policyViolation, destinationComponent);
                        persist(clonedPolicyViolation);
                    }
                }
            }

            return project;
        });

        if(oldLatestProject.get() != null) {
            // if we removed isLatest flag from old version, dispatch update event for the old version
            Event.dispatch(new IndexEvent(IndexEvent.Action.UPDATE, oldLatestProject.get()));
        }
        Event.dispatch(new IndexEvent(IndexEvent.Action.CREATE, clonedProject));
        commitSearchIndex(true, Project.class);

        if(clonedProject.getParent() != null && clonedProject.getParent().getCollectionLogic() != ProjectCollectionLogic.NONE) {
            Event.dispatch(new ProjectMetricsUpdateEvent(clonedProject.getParent().getUuid()));
        }
        Event.dispatch(new ProjectMetricsUpdateEvent(clonedProject.getUuid()));

        return clonedProject;
    }

    private static Set<UUID> parseDirectDependenciesUuids(
            final JsonMapper jsonMapper,
            final String directDependencies) throws IOException {
        final var uuids = new HashSet<UUID>();
        try (final JsonParser jsonParser = jsonMapper.createParser(directDependencies)) {
            JsonToken currentToken = jsonParser.nextToken();
            if (currentToken != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("""
                        Expected directDependencies to be a JSON array, \
                        but encountered token: %s""".formatted(currentToken));
            }

            while (jsonParser.nextToken() != null) {
                if (jsonParser.currentToken() == JsonToken.FIELD_NAME
                    && "uuid".equals(jsonParser.currentName())
                    && jsonParser.nextToken() == JsonToken.VALUE_STRING) {
                    uuids.add(UUID.fromString(jsonParser.getValueAsString()));
                }
            }
        }

        return uuids;
    }

    /**
     * Deletes a Project and all objects dependant on the project.
     * @param project the Project to delete
     * @param commitIndex specifies if the search index should be committed (an expensive operation)
     */
    @Override
    public void recursivelyDelete(final Project project, final boolean commitIndex) {
        Project parent = project.getParent();

        if (project.getChildren() != null) {
            for (final Project child: project.getChildren()) {
                recursivelyDelete(child, false);
            }
        }
        pm.getFetchPlan().setDetachmentOptions(FetchPlan.DETACH_LOAD_FIELDS);
        final Project result = pm.getObjectById(Project.class, project.getId());
        Event.dispatch(new IndexEvent(IndexEvent.Action.DELETE, result));
        commitSearchIndex(commitIndex, Project.class);

        deleteAnalysisTrail(project);
        deleteViolationAnalysisTrail(project);
        deleteMetrics(project);
        deleteFindingAttributions(project);
        deletePolicyViolations(project);
        deleteComponents(project);

        for (final ServiceComponent s: getAllServiceComponents(project)) {
            recursivelyDelete(s, false);
        }
        deleteBoms(project);
        deleteVexs(project);
        removeProjectFromNotificationRules(project);
        removeProjectFromPolicies(project);
        delete(project.getMetadata());
        delete(project.getProperties());
        delete(getAllBoms(project));
        delete(project.getChildren());
        delete(project);

        // if project had a parent we need to re-calculate parents metrics to update collection projects
        if(commitIndex && parent != null && parent.getCollectionLogic() != ProjectCollectionLogic.NONE) {
            Event.dispatch(new ProjectMetricsUpdateEvent(parent.getUuid()));
        }
    }

    /**
     * Deletes a list of Projects (identified by their UUIDs) and all objects dependent on them.
     * @param uuids the UUIDs of the Projects to delete
     *
     * NB: if ON DELETE rules had been set up, this would be as simple as "delete from project where ..."
     */
    @Override
    public void deleteProjectsByUUIDs(Collection<UUID> uuids) {
        final var errorByUUID = new HashMap<String, String>();

        final Query<Project> projectsQuery = this.getObjectsByUuidsQuery(Project.class, uuids.stream().toList());
        List<Project> projects = projectsQuery.executeList();

        for (UUID uuid: uuids) {
            if (projects.stream().map(Project::getUuid).noneMatch(uuid::equals)) {
                errorByUUID.put(uuid.toString(), "Project not found");
            }
        }

        Set<Long> accessibleProjectIds = new HashSet<>();
        for (Project project: projects) {
            if (!hasAccess(principal, project)) {
                errorByUUID.put(String.valueOf(project.getUuid()), "Access denied to project");
            } else {
                accessibleProjectIds.add(project.getId());
            }
        }

        if (!errorByUUID.isEmpty()) {
            throw ProjectOperationException.forDeletion(errorByUUID);
        }

        Long[] projectIDsArray = accessibleProjectIds.toArray(Long[]::new);
        String commaSeparatedProjectIDs = accessibleProjectIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        var queryParameter = DbUtil.isMssql() ? commaSeparatedProjectIDs : projectIDsArray;
        String inExpression = DbUtil.isMssql() ? "IN(SELECT value FROM STRING_SPLIT(?, ','))" : "= ANY(?)";

        runInTransaction(() -> {
            try (var ignored = new ScopedCustomization(pm).withProperty(PROPERTY_QUERY_SQL_ALLOWALL, "true")) {
                Query<?> sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "PROJECTMETRICS" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """                        
                    DELETE FROM "DEPENDENCYMETRICS" WHERE "PROJECT_ID" = ANY(?);
                    """.replaceAll(Pattern.quote("= ANY(?)"), inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """                        
                    DELETE FROM "FINDINGATTRIBUTION" WHERE "PROJECT_ID" = ANY(?);
                    """.replaceAll(Pattern.quote("= ANY(?)"), inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "COMPONENTS_VULNERABILITIES" WHERE "COMPONENT_ID" IN (
                        SELECT "ID" FROM "COMPONENT" WHERE "PROJECT_ID" = ANY(?)
                    );
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "ANALYSISCOMMENT" WHERE "ANALYSIS_ID" IN (
                        SELECT "ID" FROM "ANALYSIS" WHERE "PROJECT_ID" = ANY(?)
                    );
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """                        
                    DELETE FROM "ANALYSIS" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """                        
                    DELETE FROM "COMPONENT_PROPERTY" WHERE "COMPONENT_ID" IN (
                        SELECT "ID" FROM "COMPONENT" WHERE "PROJECT_ID" = ANY(?)
                    );
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                // Deletion with CTEs does not work with H2, but verified on Postgres and MS SQL Server
                if (!DbUtil.isH2()) {
                    if (DbUtil.isPostgreSQL()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH RECURSIVE c_family("ID", "PARENT_COMPONENT_ID") AS (
                                SELECT "COMPONENT"."ID", "PARENT_COMPONENT_ID"
                                FROM "COMPONENT"
                                JOIN "PROJECT" ON "PROJECT_ID" = "PROJECT"."ID"
                                WHERE "PROJECT"."ID" = ANY(?)
                                UNION ALL
                                SELECT "COMPONENT"."ID", "COMPONENT"."PARENT_COMPONENT_ID"
                                FROM c_family, "COMPONENT"
                                WHERE "COMPONENT"."ID" = c_family."PARENT_COMPONENT_ID"
                            )
                            DELETE FROM "COMPONENT"
                              WHERE "ID" IN (SELECT "ID" FROM c_family);
                            """
                        );
                    }
                    if (DbUtil.isMssql()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH c_family AS (
                                SELECT COMPONENT.ID, COMPONENT.PARENT_COMPONENT_ID
                                FROM COMPONENT
                                JOIN PROJECT ON COMPONENT.PROJECT_ID = PROJECT.ID
                                WHERE PROJECT.ID IN (SELECT value FROM STRING_SPLIT(?, ','))
                                UNION ALL
                                SELECT COMPONENT.ID, COMPONENT.PARENT_COMPONENT_ID
                                FROM COMPONENT
                                INNER JOIN c_family ON COMPONENT.ID = c_family.PARENT_COMPONENT_ID
                            )
                            DELETE FROM COMPONENT
                            WHERE ID IN (SELECT ID FROM c_family);
                            """
                        );
                    }
                    executeAndCloseWithArray(sqlQuery, queryParameter);
                }

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """                        
                    DELETE FROM "COMPONENT" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "BOM" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "PROJECT_METADATA" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "PROJECT_PROPERTY" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "PROJECTS_TAGS" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "NOTIFICATIONRULE_PROJECTS" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                if (!DbUtil.isH2()) {
                    if (DbUtil.isPostgreSQL()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH v_ids AS (
                                SELECT "VIOLATIONANALYSIS"."ID"
                                FROM "VIOLATIONANALYSIS"
                                JOIN "PROJECT" ON "PROJECT_ID" = "PROJECT"."ID"
                                WHERE "PROJECT"."ID" = ANY(?)
                            )
                            DELETE FROM "VIOLATIONANALYSISCOMMENT"
                            USING v_ids
                            WHERE "VIOLATIONANALYSIS_ID" = v_ids."ID";
                            """
                        );
                    }
                    if (DbUtil.isMssql()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH v_ids AS (
                                SELECT VIOLATIONANALYSIS.ID
                                FROM VIOLATIONANALYSIS
                                JOIN PROJECT ON PROJECT_ID = PROJECT.ID
                                WHERE PROJECT.ID IN (SELECT value FROM STRING_SPLIT(?, ','))
                            )
                            DELETE VAC
                            FROM VIOLATIONANALYSISCOMMENT AS VAC
                            JOIN v_ids ON VAC.VIOLATIONANALYSIS_ID = v_ids.ID;
                            """);
                    }
                    executeAndCloseWithArray(sqlQuery, queryParameter);
                }

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "VIOLATIONANALYSIS" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "POLICYVIOLATION" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "POLICY_PROJECTS" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "PROJECT_ACCESS_TEAMS" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                    DELETE FROM "VEX" WHERE "PROJECT_ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);

                if (!DbUtil.isH2()) {
                    if (DbUtil.isPostgreSQL()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH sc_cte AS (
                              SELECT "SERVICECOMPONENT"."ID" FROM "SERVICECOMPONENT"
                              JOIN "PROJECT" ON "SERVICECOMPONENT"."PROJECT_ID" = "PROJECT"."ID"
                              WHERE "PROJECT"."ID" = ANY(?)
                            )
                            DELETE FROM "SERVICECOMPONENTS_VULNERABILITIES"
                            USING sc_cte
                            WHERE "SERVICECOMPONENTS_VULNERABILITIES"."SERVICECOMPONENT_ID" = sc_cte."ID";
                            """
                        );
                    }
                    if (DbUtil.isMssql()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH sc_cte AS (
                                SELECT SERVICECOMPONENT.ID
                                FROM SERVICECOMPONENT
                                JOIN PROJECT ON SERVICECOMPONENT.PROJECT_ID = PROJECT.ID
                                WHERE PROJECT.ID IN (SELECT value FROM STRING_SPLIT(?, ','))
                            )
                            DELETE SVCV
                            FROM SERVICECOMPONENTS_VULNERABILITIES AS SVCV
                            JOIN sc_cte ON SVCV.SERVICECOMPONENT_ID = sc_cte.ID;
                            """
                        );
                    }
                    executeAndCloseWithArray(sqlQuery, queryParameter);

                    if (DbUtil.isPostgreSQL()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH RECURSIVE sc_family("ID", "PARENT_SERVICECOMPONENT_ID") AS (
                                SELECT "SERVICECOMPONENT"."ID", "PARENT_SERVICECOMPONENT_ID"
                                FROM "SERVICECOMPONENT"
                                JOIN "PROJECT" ON "PROJECT"."ID" = "SERVICECOMPONENT"."PROJECT_ID"
                                WHERE "PROJECT"."ID" = ANY(?)
                              UNION ALL
                                SELECT "SERVICECOMPONENT"."ID", "SERVICECOMPONENT"."PARENT_SERVICECOMPONENT_ID"
                                FROM sc_family, "SERVICECOMPONENT"
                                WHERE "SERVICECOMPONENT"."ID" = sc_family."PARENT_SERVICECOMPONENT_ID"
                            )
                            DELETE FROM "SERVICECOMPONENT"
                              WHERE "ID" = ANY(SELECT "ID" FROM sc_family);
                            """);
                    }
                    if (DbUtil.isMssql()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                                WITH sc_family AS (
                                   SELECT
                                       SERVICECOMPONENT.ID,
                                       SERVICECOMPONENT.PARENT_SERVICECOMPONENT_ID
                                   FROM SERVICECOMPONENT
                                   JOIN PROJECT ON PROJECT.ID = SERVICECOMPONENT.PROJECT_ID
                                   WHERE PROJECT.ID IN (SELECT value FROM STRING_SPLIT(?, ','))
                                   UNION ALL
                                   SELECT
                                       SERVICECOMPONENT.ID,
                                       SERVICECOMPONENT.PARENT_SERVICECOMPONENT_ID
                                   FROM SERVICECOMPONENT
                                   INNER JOIN sc_family ON SERVICECOMPONENT.ID = sc_family.PARENT_SERVICECOMPONENT_ID
                               )
                               DELETE FROM SERVICECOMPONENT
                               WHERE ID IN (SELECT ID FROM sc_family);
                            """
                        );
                    }
                    executeAndCloseWithArray(sqlQuery, queryParameter);

                    if (DbUtil.isPostgreSQL()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                            WITH RECURSIVE p_family("ID", "PARENT_PROJECT_ID") AS (
                                SELECT "PROJECT"."ID", "PARENT_PROJECT_ID"
                                FROM "PROJECT"
                                WHERE "PROJECT"."ID" = ANY(?)
                              UNION ALL
                                SELECT "PROJECT"."ID", "PROJECT"."PARENT_PROJECT_ID"
                                FROM p_family, "PROJECT"
                                WHERE "PROJECT"."ID" = p_family."PARENT_PROJECT_ID"
                            )
                            DELETE FROM "PROJECT"
                              WHERE "ID" = ANY(SELECT "ID" FROM p_family);
                            """
                        );
                    }
                    if (DbUtil.isMssql()) {
                        sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """
                              WITH p_family AS (
                                  SELECT
                                      PROJECT.ID,
                                      PROJECT.PARENT_PROJECT_ID
                                  FROM PROJECT
                                  WHERE PROJECT.ID IN (SELECT value FROM STRING_SPLIT(?, ','))
                                  UNION ALL
                                  SELECT
                                      PROJECT.ID,
                                      PROJECT.PARENT_PROJECT_ID
                                  FROM PROJECT
                                  INNER JOIN p_family ON PROJECT.ID = p_family.PARENT_PROJECT_ID
                              )
                              DELETE FROM PROJECT
                              WHERE ID IN (SELECT ID FROM p_family);
                            """
                        );
                    }
                    executeAndCloseWithArray(sqlQuery, queryParameter);
                }

                sqlQuery = pm.newQuery(JDOQuery.SQL_QUERY_LANGUAGE, """                        
                    DELETE FROM "PROJECT" WHERE "ID" = ANY(?);
                    """.replace("= ANY(?)", inExpression)
                );
                executeAndCloseWithArray(sqlQuery, queryParameter);
            }
        });
    }

    /**
     * Creates a key/value pair (ProjectProperty) for the specified Project.
     * @param project the Project to create the property for
     * @param groupName the group name of the property
     * @param propertyName the name of the property
     * @param propertyValue the value of the property
     * @param propertyType the type of property
     * @param description a description of the property
     * @return the created ProjectProperty object
     */
    @Override
    public ProjectProperty createProjectProperty(final Project project, final String groupName, final String propertyName,
                                                 final String propertyValue, final ProjectProperty.PropertyType propertyType,
                                                 final String description) {
        final ProjectProperty property = new ProjectProperty();
        property.setProject(project);
        property.setGroupName(groupName);
        property.setPropertyName(propertyName);
        property.setPropertyValue(propertyValue);
        property.setPropertyType(propertyType);
        property.setDescription(description);
        return persist(property);
    }

    /**
     * Returns a ProjectProperty with the specified groupName and propertyName.
     * @param project the project the property belongs to
     * @param groupName the group name of the config property
     * @param propertyName the name of the property
     * @return a ProjectProperty object
     */
    @Override
    public ProjectProperty getProjectProperty(final Project project, final String groupName, final String propertyName) {
        final Query<ProjectProperty> query = this.pm.newQuery(ProjectProperty.class, "project == :project && groupName == :groupName && propertyName == :propertyName");
        query.setRange(0, 1);
        return singleResult(query.execute(project, groupName, propertyName));
    }

    /**
     * Returns a List of ProjectProperty's for the specified project.
     * @param project the project the property belongs to
     * @return a List ProjectProperty objects
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<ProjectProperty> getProjectProperties(final Project project) {
        final Query<ProjectProperty> query = this.pm.newQuery(ProjectProperty.class, "project == :project");
        query.setOrdering("groupName asc, propertyName asc");
        return (List<ProjectProperty>)query.execute(project);
    }

    /**
     * @since 4.12.3
     */
    @Override
    public boolean bind(final Project project, final Collection<Tag> tags, final boolean keepExisting) {
        assertPersistent(project, "project must be persistent");
        assertPersistentAll(tags, "tags must be persistent");

        return callInTransaction(() -> {
            boolean modified = false;

            if (project.getTags() == null) {
                project.setTags(new HashSet<>());
            }

            if (!keepExisting) {
                final Iterator<Tag> existingTagsIterator = project.getTags().iterator();
                while (existingTagsIterator.hasNext()) {
                    final Tag existingTag = existingTagsIterator.next();
                    if (!tags.contains(existingTag)) {
                        existingTagsIterator.remove();
                        if (existingTag.getProjects() != null) {
                            existingTag.getProjects().remove(project);
                        }
                        modified = true;
                    }
                }
            }

            for (final Tag tag : tags) {
                if (!project.getTags().contains(tag)) {
                    project.getTags().add(tag);

                    if (tag.getProjects() == null) {
                        tag.setProjects(new HashSet<>(Set.of(project)));
                    } else {
                        tag.getProjects().add(project);
                    }

                    modified = true;
                }
            }

            return modified;
        });
    }

    /**
     * Binds the two objects together in a corresponding join table.
     * @param project a Project object
     * @param tags a List of Tag objects
     */
    @Override
    public void bind(final Project project, final Collection<Tag> tags) {
        bind(project, tags, /* keepExisting */ false);
    }

    /**
     * Updates the last time a bom was imported.
     * @param date the date of the last bom import
     * @param bomFormat the format and version of the bom format
     * @return the updated Project
     */
    @Override
    public Project updateLastBomImport(Project p, Date date, String bomFormat) {
        final Project project = getObjectById(Project.class, p.getId());
        project.setLastBomImport(date);
        project.setLastBomImportFormat(bomFormat);
        return persist(project);
    }

    @Override
    public boolean hasAccess(final Principal principal, final Project project) {
        if (isEnabled(ConfigPropertyConstants.ACCESS_MANAGEMENT_ACL_ENABLED)) {
            if (principal instanceof final UserPrincipal userPrincipal) {
                if (super.hasAccessManagementPermission(userPrincipal)) {
                    return true;
                }
                if (userPrincipal.getTeams() != null) {
                    for (final Team userInTeam : userPrincipal.getTeams()) {
                        for (final Team accessTeam : project.getAccessTeams()) {
                            if (userInTeam.getId() == accessTeam.getId()) {
                                return true;
                            }
                        }
                    }
                }
            } else if (principal instanceof final ApiKey apiKey ){
                if (super.hasAccessManagementPermission(apiKey)) {
                    return true;
                }
                if (apiKey.getTeams() != null) {
                    for (final Team userInTeam : apiKey.getTeams()) {
                        for (final Team accessTeam : project.getAccessTeams()) {
                            if (userInTeam.getId() == accessTeam.getId()) {
                                return true;
                            }
                        }
                    }
                }
            } else if (principal == null) {
                // This is a system request being made (e.g. MetricsUpdateTask, etc) where there isn't a principal
                return true;
            }
            return false;
        } else {
            return true;
        }
    }

    /**
     * A similar method exists in ComponentQueryManager
     */
    @Override
    void preprocessACLs(final Query<?> query, final String inputFilter, final Map<String, Object> params, final boolean bypass) {
        String projectMemberFieldName = null;
        final org.datanucleus.store.query.Query<?> internalQuery = ((JDOQuery<?>)query).getInternalQuery();
        if (!Project.class.equals(internalQuery.getCandidateClass())) {
            // NB: The query does not directly target Project, but if it has a relationship
            // with Project we can still make the ACL check work. If the query candidate
            // has EXACTLY one persistent field of type Project, we'll use that.
            // If there are more than one, or none at all, we fail to avoid unintentional behavior.
            final TypeMetadata candidateTypeMetadata = pm.getPersistenceManagerFactory().getMetadata(internalQuery.getCandidateClassName());

            for (final MemberMetadata memberMetadata : candidateTypeMetadata.getMembers()) {
                if (!Project.class.getName().equals(memberMetadata.getFieldType())) {
                    continue;
                }

                if (projectMemberFieldName != null) {
                    throw new IllegalArgumentException("Query candidate class %s has multiple members of type %s"
                            .formatted(internalQuery.getCandidateClassName(), Project.class.getName()));
                }

                projectMemberFieldName = memberMetadata.getName();
            }

            if (projectMemberFieldName == null) {
                throw new IllegalArgumentException("Query candidate class %s has no member of type %s"
                        .formatted(internalQuery.getCandidateClassName(), Project.class.getName()));
            }
        }

        if (super.principal != null && isEnabled(ConfigPropertyConstants.ACCESS_MANAGEMENT_ACL_ENABLED) && !bypass) {
            final List<Team> teams;
            if (super.principal instanceof final UserPrincipal userPrincipal) {
                teams = userPrincipal.getTeams();
                if (super.hasAccessManagementPermission(userPrincipal)) {
                    query.setFilter(inputFilter);
                    return;
                }
            } else {
                final ApiKey apiKey = ((ApiKey) super.principal);
                teams = apiKey.getTeams();
                if (super.hasAccessManagementPermission(apiKey)) {
                    query.setFilter(inputFilter);
                    return;
                }
            }
            if (teams != null && !teams.isEmpty()) {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0, teamsSize = teams.size(); i < teamsSize; i++) {
                    final Team team = super.getObjectById(Team.class, teams.get(i).getId());
                    sb.append(" ");
                    if (projectMemberFieldName != null) {
                        sb.append(projectMemberFieldName).append(".");
                    }
                    sb.append(" accessTeams.contains(:team").append(i).append(") ");
                    params.put("team" + i, team);
                    if (i < teamsSize-1) {
                        sb.append(" || ");
                    }
                }
                if (inputFilter != null && !inputFilter.isBlank()) {
                    query.setFilter(inputFilter + " && (" + sb + ")");
                } else {
                    query.setFilter(sb.toString());
                }
            }
        } else if (StringUtils.trimToNull(inputFilter) != null) {
            query.setFilter(inputFilter);
        }
    }

    /**
     * Updates a Project ACL to add the principals Team to the AccessTeams
     * This only happens if Portfolio Access Control is enabled and the @param principal is an ApyKey
     * For a UserPrincipal we don't know which Team(s) to add to the ACL,
     * See https://github.com/DependencyTrack/dependency-track/issues/1435
     * @param project
     * @param principal
     * @return True if ACL was updated
     */
    @Override
    public boolean updateNewProjectACL(Project project, Principal principal) {
        if (isEnabled(ConfigPropertyConstants.ACCESS_MANAGEMENT_ACL_ENABLED) && principal instanceof ApiKey apiKey) {
            final var apiTeam = apiKey.getTeams().stream().findFirst();
            if (apiTeam.isPresent()) {
                LOGGER.debug("adding Team to ACL of newly created project");
                final Team team = getObjectByUuid(Team.class, apiTeam.get().getUuid());
                project.addAccessTeam(team);
                persist(project);
                return true;
            } else {
                LOGGER.warn("API Key without a Team, unable to assign team ACL to project.");
            }
        }
        return false;
    }

    @Override
    public boolean hasAccessManagementPermission(final UserPrincipal userPrincipal) {
        for (Permission permission: getEffectivePermissions(userPrincipal)) {
            if (Permissions.ACCESS_MANAGEMENT.name().equals(permission.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasAccessManagementPermission(final ApiKey apiKey) {
        return hasPermission(apiKey, Permissions.ACCESS_MANAGEMENT.name());
    }


    @Override
    public PaginatedResult getChildrenProjects(final UUID uuid, final boolean includeMetrics, final boolean excludeInactive) {
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc, version desc");
        }

        var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withParent(uuid);

        if (filter != null) {
            final String filterString = ".*" + filter.toLowerCase() + ".*";
            final Tag tag = getTagByName(filter.trim());

            if (tag != null) {
                filterBuilder = filterBuilder.withFuzzyNameOrExactTag(filterString, tag);

            } else {
                filterBuilder = filterBuilder.withFuzzyName(filterString);
            }
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        query.getFetchPlan().addGroup(Project.FetchGroup.ALL.name());
        result = execute(query, params);
        if (includeMetrics) {
            // Populate each Project object in the paginated result with transitive related
            // data to minimize the number of round trips a client needs to make, process, and render.
            for (Project project : result.getList(Project.class)) {
                project.setMetrics(getMostRecentProjectMetrics(project));
            }
        }
        return result;
    }

    @Override
    public PaginatedResult getChildrenProjects(final Classifier classifier, final UUID uuid, final boolean includeMetrics, final boolean excludeInactive) {
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc");
        }

        final var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withParent(uuid)
                .withClassifier(classifier);

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        query.getFetchPlan().addGroup(Project.FetchGroup.ALL.name());
        result = execute(query, params);
        if (includeMetrics) {
            // Populate each Project object in the paginated result with transitive related
            // data to minimize the number of round trips a client needs to make, process, and render.
            for (Project project : result.getList(Project.class)) {
                project.setMetrics(getMostRecentProjectMetrics(project));
            }
        }
        return result;
    }

    @Override
    public PaginatedResult getChildrenProjects(final Tag tag, final UUID uuid, final boolean includeMetrics, final boolean excludeInactive) {
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc");
        }

        var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withParent(uuid)
                .withTag(tag);

        if (filter != null) {
            final String filterString = ".*" + filter.toLowerCase() + ".*";
            filterBuilder = filterBuilder.withFuzzyName(filterString);
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        result = execute(query, params);
        if (includeMetrics) {
            // Populate each Project object in the paginated result with transitive related
            // data to minimize the number of round trips a client needs to make, process, and render.
            for (Project project : result.getList(Project.class)) {
                project.setMetrics(getMostRecentProjectMetrics(project));
            }
        }
        return result;
    }

    @Override
    public PaginatedResult getProjectsWithoutDescendantsOf(final boolean exludeInactive, final Project project){
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc, version desc");
        }

        var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(exludeInactive);

        if (filter != null) {
            final String filterString = ".*" + filter.toLowerCase() + ".*";
            final Tag tag = getTagByName(filter.trim());

            if (tag != null) {
                filterBuilder = filterBuilder.withFuzzyNameOrExactTag(filterString, tag);

            } else {
                filterBuilder = filterBuilder.withFuzzyName(filterString);
            }
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        result = execute(query, params);

        result.setObjects(result.getList(Project.class).stream().filter(p -> !isChildOf(p, project.getUuid()) && !p.getUuid().equals(project.getUuid())).toList());
        result.setTotal(result.getObjects().size());

        return result;
    }

    @Override
    public PaginatedResult getProjectsWithoutDescendantsOf(final String name, final boolean excludeInactive, Project project){
        final PaginatedResult result;
        final Query<Project> query = pm.newQuery(Project.class);
        if (orderBy == null) {
            query.setOrdering("name asc, version desc");
        }

        var filterBuilder = new ProjectQueryFilterBuilder()
                .excludeInactive(excludeInactive)
                .withName(name);

        if (filter != null) {
            final String filterString = ".*" + filter.toLowerCase() + ".*";
            final Tag tag = getTagByName(filter.trim());

            if (tag != null) {
                filterBuilder = filterBuilder.withFuzzyNameOrExactTag(filterString, tag);

            } else {
                filterBuilder = filterBuilder.withFuzzyName(filterString);
            }
        }

        final String queryFilter = filterBuilder.buildFilter();
        final Map<String, Object> params = filterBuilder.getParams();

        preprocessACLs(query, queryFilter, params, false);
        result = execute(query, params);

        result.setObjects(result.getList(Project.class).stream().filter(p -> !isChildOf(p, project.getUuid()) && !p.getUuid().equals(project.getUuid())).toList());
        result.setTotal(result.getObjects().size());

        return result;
    }

    /**
     * Check whether a {@link Project} with a given {@code name} and {@code version} exists.
     *
     * @param name    Name of the {@link Project} to check for
     * @param version Version of the {@link Project} to check for
     * @return {@code true} when a matching {@link Project} exists, otherwise {@code false}
     * @since 4.9.0
     */
    @Override
    public boolean doesProjectExist(final String name, final String version) {
        final Query<Project> query = pm.newQuery(Project.class);
        if (version != null) {
            query.setFilter("name == :name && version == :version");
            query.setNamedParameters(Map.of(
                    "name", name,
                    "version", version
            ));
        } else {
            // Version is optional for projects, but using null
            // for parameter values bypasses the query compilation cache.
            // https://github.com/DependencyTrack/dependency-track/issues/2540
            query.setFilter("name == :name && version == null");
            query.setNamedParameters(Map.of(
                    "name", name
            ));
        }
        query.setResult("count(this)");
        try {
            return query.executeResultUnique(Long.class) > 0;
        } finally {
            query.closeAll();
        }
    }

    private static boolean isChildOf(Project project, UUID uuid) {
        boolean isChild = false;
        if (project.getParent() != null){
            if (project.getParent().getUuid().equals(uuid)){
                return true;
            } else {
                isChild = isChildOf(project.getParent(), uuid);
            }
        }
        return isChild;
    }

    private static boolean hasActiveChild(Project project) {
        boolean hasActiveChild = false;
        if (project.getChildren() != null){
            for (Project child: project.getChildren()) {
                if (Boolean.TRUE.equals(child.isActive()) || hasActiveChild) {
                    return true;
                } else {
                    hasActiveChild = hasActiveChild(child);
                }
            }
        }
        return hasActiveChild;
    }

    private List<ProjectVersion> getProjectVersions(Project project) {
        final Query<Project> query = pm.newQuery(Project.class);
        query.setFilter("name == :name");
        query.setParameters(project.getName());
        query.setResult("uuid, version, active");
        return query.executeResultList(ProjectVersion.class);
    }
}
