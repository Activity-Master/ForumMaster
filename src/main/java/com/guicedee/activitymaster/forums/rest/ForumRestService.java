package com.guicedee.activitymaster.forums.rest;

import com.google.inject.Inject;
import com.guicedee.activitymaster.forums.ForumApi;
import com.guicedee.activitymaster.forums.ForumModels.*;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("{enterprise}/forums")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class ForumRestService {
    @Inject private ForumApi api;

    @POST public Uni<Forum> create(@PathParam("enterprise") String enterprise, Create request) {
        return api.create(enterprise, request);
    }
    @GET @Path("{id}") public Uni<Forum> find(@PathParam("enterprise") String enterprise,
                                                        @PathParam("id") UUID id) {
        return api.find(enterprise, id);
    }
    @GET public Uni<Page<Forum>> list(@PathParam("enterprise") String enterprise,
                                               @QueryParam("offset") @DefaultValue("0") int offset,
                                               @QueryParam("limit") @DefaultValue("50") int limit) {
        return api.list(enterprise, offset, limit);
    }
    @POST @Path("{id}/posts") public Uni<Post> post(@PathParam("enterprise") String enterprise,
                                                             @PathParam("id") UUID id, PublishPost request) {
        return api.post(enterprise, id, request);
    }
    @GET @Path("{id}/posts") public Uni<Page<Post>> posts(@PathParam("enterprise") String enterprise,
                                                                      @PathParam("id") UUID id,
                                                                      @QueryParam("offset") @DefaultValue("0") int offset,
                                                                      @QueryParam("limit") @DefaultValue("50") int limit) {
        return api.posts(enterprise, id, offset, limit);
    }
    @PUT @Path("{id}/subscribers/{partyId}") public Uni<Forum> addSubscriber(
            @PathParam("enterprise") String enterprise, @PathParam("id") UUID id,
            @PathParam("partyId") UUID partyId) {
        return api.addSubscriber(enterprise, id, partyId);
    }
    @DELETE @Path("{id}/subscribers/{partyId}") public Uni<Forum> removeSubscriber(
            @PathParam("enterprise") String enterprise, @PathParam("id") UUID id,
            @PathParam("partyId") UUID partyId) {
        return api.removeSubscriber(enterprise, id, partyId);
    }
    @PUT @Path("{id}/subscribers") public Uni<Forum> updateSubscribers(
            @PathParam("enterprise") String enterprise, @PathParam("id") UUID id, UpdateSubscribers request) {
        return api.updateSubscribers(enterprise, id, request);
    }
    @DELETE @Path("{id}/membership") public Uni<Void> leave(@PathParam("enterprise") String enterprise,
                                                               @PathParam("id") UUID id) {
        return api.leave(enterprise, id);
    }
}
