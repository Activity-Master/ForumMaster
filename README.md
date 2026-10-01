# Forum Master

Forum Master stores discussions in the existing FSDM model. A forum is an `Arrangement` with a
title and context; subscriptions are `ArrangementXInvolvedParty` links; posts are `Event` rows with
private `ResourceItem` bodies. The module creates no forum, post, or subscriber table.

The consuming host binds `ForumIdentityProvider` to a verified, call-scoped organic party,
enterprise, Personal/Social/Work context, and ActivityMaster credential. The default provider
denies access. Personal and Social context owners must be the verified party; Work must use the
authorized enterprise. The service checks the live party and FSDM grants on each operation. A
party ID in a request is only a subscription target, never the actor's identity.

For plugin calls, supply the optional fifth `ForumIdentity` field with a host-bound
`PluginModels.Invocation`, keeping the authenticated user's credential. The service
requires current installation, Forum Master declaration, user consent and administrator
permission before its normal grants and membership checks. REST and GraphQL share this
boundary; GraphQL cannot supply or clear the invocation. The original four-argument
constructor remains available for ordinary calls.

`NotificationIdentityProvider` must preserve the same invocation. Shared posts also
require a Notification Master declaration and separate user consent, as well as the
existing publishing grants; denial rolls back the post. `ForumApi` records plugin
initiation audits for successful mutations while records retain Forum Master's source
ID. Direct low-level writes must compose their own audit. See
[the plugin contract](../core/docs/plugins.md) for provisioning and lifecycle details.

The creator is the forum moderator and stays subscribed. Only that moderator may add, remove, or
replace subscribers. Subscribers must be live organic involved parties in the enterprise.
Subscribers may read and post; other callers get 404 for that forum. A subscriber may leave, which
ends only their own FSDM link. The moderator cannot leave. At most 50 parties can be subscribed.
Personal forums have only their creator. Removing a subscriber ends the relationship's effective
date; adding them later creates a new relationship, preserving the previous membership history.
Changes to a forum's subscriber list affect future post notifications. Already published
notifications retain their original recipients and state.

REST base: `/{enterprise}/forums` (mounted under the host's REST prefix).

| Method | Path | Action |
| --- | --- | --- |
| `POST` | `/` | Create `{realm, ownerId, title, subscribers}` |
| `GET` | `/` | List the caller's forums (`offset`, `limit`) |
| `GET` | `/{id}` | Read a forum and its current subscribers |
| `POST` | `/{id}/posts` | Post `{text}` |
| `GET` | `/{id}/posts` | List posts in creation order (`offset`, `limit`) |
| `PUT` | `/{id}/subscribers/{partyId}` | Add one organic party |
| `DELETE` | `/{id}/subscribers/{partyId}` | Remove one party |
| `PUT` | `/{id}/subscribers` | Replace the audience with `{subscribers: [...]}` |
| `DELETE` | `/{id}/membership` | Leave the forum as a subscriber |

Writes require a caller-owned stateless transaction. Titles are single-line and at most 150 UTF-16
code units. Post text is plain Unicode, at most 65,536 UTF-16 code units; clients must escape it
when rendering. Paging accepts offsets 0..10,000 and limits 1..100.

`ForumApi.post` snapshots the current subscribers and stores the post and its `forum.post`
notification in one stateless FSDM transaction. Notification publication failure rolls back the
post as well, so retrying a failed publication cannot duplicate a previously saved post. The poster
is excluded. Channel delivery starts after the shared transaction commits. Notification Master
stores the notice and sends it on its event bus channel. The
notification data contains the forum and post IDs. The host must bind
`NotificationIdentityProvider` to the same verified caller. The caller needs Notification Master's
system and party-row read grants plus `notifications.publish` in the current scope. Recipients need
their own Notification Master read grants to open the notices. Delivery attempts remain governed
by Notification Master. Channel failures do not undo a committed post or its stored notification.

The low-level `IForumService.post` writes forum content only. Hosts that call that service directly
must compose notification publication in their own transaction; the REST API uses `ForumApi.post`
and supplies the shared transaction automatically.

## GraphQL

`ForumGraphQLSchemaProvider` contributes typed operations to the host's GuicedEE
GraphQL endpoint, normally `/graphql`. JPMS and `META-INF/services` both register
the provider. Add `forum-master` to the host; its REST and GraphQL adapters share
`ForumApi`, the verified identity providers and the existing FSDM storage.

Queries: `forum`, `forums`, `forumPosts`.
Mutations: `forumCreate`, `forumPost`, `forumAddSubscriber`,
`forumRemoveSubscriber`, `forumUpdateSubscribers`, `forumLeave`.
Every operation requires `enterprise`; forum targets use `forumId`, and individual
subscription changes use `partyId`. Pages default to offset 0 and limit 50 and
retain the REST bounds and `hasMore` flag. Successful `forumLeave` returns `true`.

```graphql
mutation CreateForum($input: ForumCreateInput!) {
  forumCreate(enterprise: "Example", input: $input) {
    id realm ownerId title subscribers
  }
}
```

`ForumCreateInput` contains `realm`, `ownerId`, `title` and optional `subscribers`
(default `[]`). `ForumPostInput` contains `text`; `ForumSubscribersInput` contains
the complete desired `subscribers` list. The supplied realm/owner must match the
verified host context; subscriber IDs identify targets and never the caller.

The adapter captures the server `RoutingContext` while subscribing to the API.
`ForumApi.post` captures both forum and notification identities before opening
its transaction, compares them when an audience needs notification publication,
and preserves atomic rollback and post-commit delivery. A single-subscriber post
does not require notification publishing authority. GraphQL variables cannot
supply an identity or token.

Errors expose `extensions.code` as `FORBIDDEN`, `NOT_FOUND`, `BAD_USER_INPUT` or
`INTERNAL_SERVER_ERROR`; messages omit internal exception and database details.
Inaccessible forums remain indistinguishable from missing ones.

`CommunicationsGraphQLTest` in this module composes forums, conversations and
notifications through the actual HTTP endpoint and a canonical PostgreSQL test
database. It covers queries, mutations, grants, rollback, delivery, current
membership and concurrent identity isolation. `CommunicationsGraphQLContractTest`
also checks schema composition, error sanitization and 64-bit notification values.
