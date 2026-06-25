/**
 * Elasticsearch-backed public post search.
 *
 * <p>The search index is a read model. Content, User, Storage, and Counter remain the source
 * modules; Search consumes reliable events and rebuilds documents from current facts.
 */
package com.platform.search;
