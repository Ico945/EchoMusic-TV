import { execFile } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const projectRoot = resolve(__dirname, '../../..');

export interface TvBuildArtifact {
  outputDir: string;
  cleanup: () => Promise<void>;
}

export async function buildTvArtifact(): Promise<TvBuildArtifact> {
  const outputDir = await mkdtemp(join(tmpdir(), 'echomusic-tv-build-'));

  try {
    await execFileAsync(
      process.execPath,
      [
        resolve(projectRoot, 'node_modules/vite/bin/vite.js'),
        'build',
        '--config',
        resolve(projectRoot, 'vite.tv.config.mts'),
        '--outDir',
        outputDir,
      ],
      { cwd: projectRoot },
    );
  } catch (error) {
    await rm(outputDir, { force: true, recursive: true });
    throw error;
  }

  return {
    outputDir,
    cleanup: () => rm(outputDir, { force: true, recursive: true }),
  };
}
