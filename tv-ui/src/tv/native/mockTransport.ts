import type { NativeTransport } from './transport';

export class MockTransport implements NativeTransport {
  readonly sent: string[] = [];
  private readonly subscribers = new Set<(message: string) => void>();

  get subscriberCount(): number {
    return this.subscribers.size;
  }

  send(message: string): void {
    this.sent.push(message);
  }

  subscribe(handler: (message: string) => void): () => void {
    this.subscribers.add(handler);
    return () => this.subscribers.delete(handler);
  }

  receive(message: unknown): void {
    const encoded = typeof message === 'string' ? message : JSON.stringify(message);
    for (const handler of this.subscribers) handler(encoded);
  }
}
