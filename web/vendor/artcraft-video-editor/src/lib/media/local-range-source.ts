// Clipper host extension: reopen local videos through bounded HTTP range reads.
// Files imported in the current tab still use BlobSource's disk-backed File reads.
import { BlobSource, UrlSource } from 'mediabunny';
export type RangeFile = File & { clipperUrl?: string };
export function mediaSourceForFile(file: File) {
  const url=(file as RangeFile).clipperUrl;
  return url ? new UrlSource(url, {maxCacheSize: 16 * 1024 * 1024, getRetryDelay: attempt => attempt < 3 ? 2 ** attempt : null}) : new BlobSource(file);
}
