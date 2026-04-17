import { GrpcServer } from './grpc/server.js';
import { VoyagerAgent } from './agents/VoyagerAgent.js';

async function main() {
    console.log('🤖 Initializing Steve AI Brain...');

    const agent = new VoyagerAgent();
    const server = new GrpcServer();

    await agent.initialize();

    // Hook the agent logic into the gRPC server streams
    server.setOnStateReceived(async (botState, stream) => {
        try {
            await agent.evaluateAndAct(botState, stream);
        } catch (error) {
            console.error('Agent Loop Error:', error);
        }
    });

    server.start(50051);
}

main().catch(console.error);
