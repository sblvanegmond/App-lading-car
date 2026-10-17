/**
 * Starting the sync workflow from the phone.
 *
 * Optional: it needs a fine-grained GitHub token with "Actions: read and
 * write" on the repository. Without one the workflow simply runs on its
 * schedule.
 */

import { resolveRepo } from './config.js';

export class GitHubError extends Error {
  constructor(message) {
    super(message);
    this.name = 'GitHubError';
  }
}

export async function triggerSync(sync, { fetchImpl = globalThis.fetch, location = globalThis.location } = {}) {
  const token = (sync?.githubToken || '').trim();
  if (!token) throw new GitHubError('Vul bij Instellingen een GitHub-token in om zelf te kunnen synchroniseren.');
  const repo = resolveRepo(sync, location);
  if (!repo) throw new GitHubError('Vul bij Instellingen de GitHub-repository in.');

  const headers = { Accept: 'application/vnd.github+json', Authorization: `Bearer ${token}` };
  const base = `https://api.github.com/repos/${repo.owner}/${repo.repo}`;

  const info = await fetchImpl(base, { headers });
  if (info.status === 401) throw new GitHubError('GitHub weigert het token. Is het verlopen?');
  if (info.status === 404) throw new GitHubError(`Repository ${repo.owner}/${repo.repo} niet gevonden met dit token.`);
  if (!info.ok) throw new GitHubError(`GitHub antwoordde met ${info.status}.`);
  const { default_branch: ref } = await info.json();

  const response = await fetchImpl(`${base}/actions/workflows/${encodeURIComponent(sync.workflow || 'coach-sync.yml')}/dispatches`, {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ref }),
  });
  if (response.status === 204) return { ref };
  if (response.status === 403) throw new GitHubError('Het token mag geen workflows starten. Geef het "Actions: read and write".');
  if (response.status === 404) {
    throw new GitHubError(`Workflow niet gevonden op ${ref}. Staat coach-sync.yml op de standaardbranch?`);
  }
  throw new GitHubError(`Workflow starten mislukt (${response.status}).`);
}
