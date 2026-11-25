export type ModelTier = "fast" | "reasoning" | "vision" | "local";

export interface ModelCompletionRequest {
  tier: ModelTier;
  systemPrompt: string;
  userPrompt: string;
  imageBase64?: string;
}

export interface ModelCompletionResponse {
  provider: string;
  model: string;
  text: string;
  stubbed: boolean;
}

export interface ModelProvider {
  readonly name: string;
  supports(tier: ModelTier): boolean;
  complete(req: ModelCompletionRequest): Promise<ModelCompletionResponse>;
}

export class ModelGateway {
  private readonly providers: ModelProvider[] = [];

  registerProvider(provider: ModelProvider): void {
    this.providers.push(provider);
  }

  async complete(req: ModelCompletionRequest): Promise<ModelCompletionResponse> {
    const provider = this.providers.find((item) => item.supports(req.tier));
    if (!provider) {
      return {
        provider: "stub",
        model: `unconfigured-${req.tier}`,
        text: "[ModelGateway] No provider configured yet; add API keys in .env to enable live inference.",
        stubbed: true,
      };
    }
    return provider.complete(req);
  }
}
