import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export interface SkillCommand {
    type: string;
    payload: any;
}

export interface Skill {
    id: string;
    name: string;
    description: string;
    commands: SkillCommand[];
}

export class SkillLibrary {
    private skills: Map<string, Skill> = new Map();
    private skillFolder: string;

    constructor(folderPath: string = path.join(__dirname, 'imported_skills')) {
        this.skillFolder = folderPath;
    }

    public async loadAllSkills() {
        if (!fs.existsSync(this.skillFolder)) {
            console.log('Skill library folder not found, skipping...');
            return;
        }

        const files = fs.readdirSync(this.skillFolder);
        for (const file of files) {
            if (file.endsWith('.json')) {
                const fullPath = path.join(this.skillFolder, file);
                try {
                    const content = fs.readFileSync(fullPath, 'utf8');
                    const skill = JSON.parse(content) as Skill;
                    this.skills.set(skill.id, skill);
                    console.log(`[SkillLibrary] Loaded skill: ${skill.id} - ${skill.name}`);
                } catch (e) {
                    console.error(`Failed to load skill from ${file}:`, e);
                }
            }
        }
    }

    public getSkill(id: string): Skill | undefined {
        return this.skills.get(id);
    }

    public getAllSkillsDescription(): string {
        let desc = 'Available Skills:\n';
        for (const skill of this.skills.values()) {
            desc += `- ${skill.id}: ${skill.description}\n`;
        }
        return desc;
    }
}
