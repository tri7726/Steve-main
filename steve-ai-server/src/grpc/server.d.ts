import * as grpc from '@grpc/grpc-js';
export declare class GrpcServer {
    private server;
    private onStateReceived?;
    constructor();
    private handleStreamControl;
    setOnStateReceived(callback: (state: any, stream: grpc.ServerDuplexStream<any, any>) => void): void;
    start(port?: number): void;
}
//# sourceMappingURL=server.d.ts.map