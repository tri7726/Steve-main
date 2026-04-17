import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';
import path from 'path';
import { fileURLToPath } from 'url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Trỏ file proto trực tiếp lên source code Java (1 điểm thay đổi duy nhất)
const PROTO_PATH = path.resolve(__dirname, '../../../src/main/proto/steve_bot.proto');
const packageDefinition = protoLoader.loadSync(PROTO_PATH, {
    keepCase: true,
    longs: String,
    enums: String,
    defaults: true,
    oneofs: true
});
const protoDescriptor = grpc.loadPackageDefinition(packageDefinition);
const steveai = protoDescriptor.steveai;
export class GrpcServer {
    server;
    onStateReceived;
    constructor() {
        this.server = new grpc.Server();
        // Gắn method StreamControl từ proto vào file ts này
        this.server.addService(steveai.SteveAiService.service, {
            StreamControl: this.handleStreamControl.bind(this)
        });
    }
    handleStreamControl(call) {
        console.log('🔗 Java Bot Connected to gRPC Server via StreamControl');
        call.on('data', (botState) => {
            // Khi Bot gửi tick/health và các info lên
            if (this.onStateReceived) {
                this.onStateReceived(botState, call);
            }
        });
        call.on('end', () => {
            console.log('🔗 Java Bot Disconnected');
            call.end();
        });
        call.on('error', (err) => {
            console.error('gRPC Stream Error:', err);
        });
    }
    setOnStateReceived(callback) {
        this.onStateReceived = callback;
    }
    start(port = 50051) {
        this.server.bindAsync(`0.0.0.0:${port}`, grpc.ServerCredentials.createInsecure(), (err, boundPort) => {
            if (err) {
                console.error('Failed to bind gRPC server:', err);
                return;
            }
            console.log(`🚀 gRPC AI Server is running on port ${boundPort}`);
        });
    }
}
//# sourceMappingURL=server.js.map