package vigil.connectors;

import java.time.Duration;

/**
 * The timeouts of this module's connectors (AR-TM-01): connect, monitoring source connection test,
 * Claude and OpenAI chat, local model chat, embeddings with one text and with more texts, each Jira
 * call, each Teams post and the SMTP read and write.
 */
record ConnectorTimings(
    Duration connect,
    Duration monitoringTest,
    Duration chat,
    Duration localChat,
    Duration embedOne,
    Duration embedBatch,
    Duration jira,
    Duration teams,
    Duration smtp) {}
